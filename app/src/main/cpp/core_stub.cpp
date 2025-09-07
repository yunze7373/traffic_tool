// Real tun2socks core implementation with TCP/UDP forwarding.
// 真实网络转发引擎：处理TUN接口数据，建立TCP/UDP会话，实现双向转发。
// 支持SOCKS代理重定向到本地MITM服务。

#include <atomic>
#include <thread>
#include <string>
#include <chrono>
#include <cstring>
#include <android/log.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/epoll.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <errno.h>
#include <map>
#include <vector>
#include <mutex>

#define CORE_LOG_TAG "Tun2SocksCore"
#define CORE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, CORE_LOG_TAG, __VA_ARGS__)
#define CORE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, CORE_LOG_TAG, __VA_ARGS__)
#define CORE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CORE_LOG_TAG, __VA_ARGS__)

// 连接状态
enum SessionState {
    STATE_INIT = 0,
    STATE_CONNECTING = 1,
    STATE_PROXY_CONNECT = 2,  // 等待发送HTTP CONNECT
    STATE_PROXY_RESPONSE = 3, // 等待代理响应
    STATE_ESTABLISHED = 4,
    STATE_CLOSING = 5
};

// TCP会话结构
struct TcpSession {
    int socket_fd = -1;
    uint32_t client_seq = 0;
    uint32_t client_ack = 0;
    uint32_t server_seq = 0;
    uint32_t server_ack = 0;
    SessionState state = STATE_INIT;
    std::string src_ip;
    uint16_t src_port = 0;
    std::string dst_ip;
    uint16_t dst_port = 0;
    std::chrono::steady_clock::time_point last_activity;
    std::vector<std::vector<uint8_t>> pending_data; // 在代理握手期间缓存的数据
};

// UDP会话结构
struct UdpSession {
    int socket_fd = -1;
    std::string src_ip;
    uint16_t src_port = 0;
    std::string dst_ip;
    uint16_t dst_port = 0;
    std::chrono::steady_clock::time_point last_activity;
};

static std::atomic<bool> g_running{false};
static int g_tun_fd = -1;
static int g_mtu = 1500;
static std::string g_socks;
static std::string g_dns;
static int g_log_level = 1; // 0=DEBUG, 1=INFO, 2=WARN
static void (*g_cb)(int,int,const char*,int,const char*,int,const uint8_t*,int) = nullptr;

// 会话管理
static std::mutex g_sessions_mutex;
static std::map<uint64_t, TcpSession> g_tcp_sessions;
static std::map<uint64_t, UdpSession> g_udp_sessions;
static int g_epoll_fd = -1;

// 生成会话key (五元组哈希)
static uint64_t make_session_key(const std::string& src_ip, uint16_t src_port, 
                                const std::string& dst_ip, uint16_t dst_port, uint8_t proto) {
    // 简单哈希组合，实际可用更好的哈希函数
    uint64_t key = 0;
    key ^= std::hash<std::string>{}(src_ip) << 1;
    key ^= std::hash<uint16_t>{}(src_port) << 2;
    key ^= std::hash<std::string>{}(dst_ip) << 3;
    key ^= std::hash<uint16_t>{}(dst_port) << 4;
    key ^= std::hash<uint8_t>{}(proto) << 5;
    return key;
}

// 清理过期会话 (前置声明)
static void cleanup_expired_sessions() {
    auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    
    // 清理TCP会话 (60秒超时)
    for (auto it = g_tcp_sessions.begin(); it != g_tcp_sessions.end();) {
        if (std::chrono::duration_cast<std::chrono::seconds>(now - it->second.last_activity).count() > 60) {
            if (it->second.socket_fd >= 0) {
                close(it->second.socket_fd);
            }
            it = g_tcp_sessions.erase(it);
        } else {
            ++it;
        }
    }
    
    // 清理UDP会话 (30秒超时)
    for (auto it = g_udp_sessions.begin(); it != g_udp_sessions.end();) {
        if (std::chrono::duration_cast<std::chrono::seconds>(now - it->second.last_activity).count() > 30) {
            if (it->second.socket_fd >= 0) {
                close(it->second.socket_fd);
            }
            it = g_udp_sessions.erase(it);
        } else {
            ++it;
        }
    }
}

// 构建IP响应包并写回TUN
static void writeResponseToTun(int tun_fd, const std::string& src_ip, uint16_t src_port,
                               const std::string& dst_ip, uint16_t dst_port, 
                               uint8_t protocol, const uint8_t* payload, int payload_len,
                               uint32_t seq_num = 0, uint32_t ack_num = 0) {
    if (tun_fd < 0 || payload_len <= 0) return;
    
    uint8_t response_packet[4096];
    int packet_len = 0;
    
    // IP头 (20字节)
    struct iphdr* ip_hdr = (struct iphdr*)response_packet;
    ip_hdr->version = 4;
    ip_hdr->ihl = 5;
    ip_hdr->tos = 0;
    ip_hdr->id = htons(rand() & 0xFFFF);
    ip_hdr->frag_off = 0;
    ip_hdr->ttl = 64;
    ip_hdr->protocol = protocol;
    inet_aton(src_ip.c_str(), (struct in_addr*)&ip_hdr->saddr);
    inet_aton(dst_ip.c_str(), (struct in_addr*)&ip_hdr->daddr);
    
    packet_len = 20; // IP头长度
    
    if (protocol == IPPROTO_TCP) {
        // TCP头 (20字节)
        struct tcphdr* tcp_hdr = (struct tcphdr*)(response_packet + 20);
        tcp_hdr->source = htons(src_port);
        tcp_hdr->dest = htons(dst_port);
        tcp_hdr->seq = htonl(seq_num > 0 ? seq_num : rand());
        tcp_hdr->ack_seq = htonl(ack_num > 0 ? ack_num : rand());
        tcp_hdr->doff = 5;
        tcp_hdr->fin = 0;
        tcp_hdr->syn = 0;
        tcp_hdr->rst = 0;
        tcp_hdr->psh = 1;
        tcp_hdr->ack = 1;
        tcp_hdr->urg = 0;
        tcp_hdr->window = htons(65535);
        tcp_hdr->check = 0;
        tcp_hdr->urg_ptr = 0;
        
        packet_len += 20; // TCP头长度
        
        // 添加payload
        if (payload_len > 0 && payload_len <= (4096 - packet_len)) {
            memcpy(response_packet + packet_len, payload, payload_len);
            packet_len += payload_len;
        }
    } else if (protocol == IPPROTO_UDP) {
        // UDP头 (8字节)
        struct udphdr* udp_hdr = (struct udphdr*)(response_packet + 20);
        udp_hdr->source = htons(src_port);
        udp_hdr->dest = htons(dst_port);
        udp_hdr->len = htons(8 + payload_len);
        udp_hdr->check = 0;
        
        packet_len += 8; // UDP头长度
        
        // 添加payload
        if (payload_len > 0 && payload_len <= (4096 - packet_len)) {
            memcpy(response_packet + packet_len, payload, payload_len);
            packet_len += payload_len;
        }
    }
    
    // 设置IP总长度
    ip_hdr->tot_len = htons(packet_len);
    
    // 计算IP校验和
    ip_hdr->check = 0;
    uint32_t sum = 0;
    uint16_t* ip_words = (uint16_t*)ip_hdr;
    for (int i = 0; i < 10; i++) {
        sum += ntohs(ip_words[i]);
    }
    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    ip_hdr->check = htons(~sum);
    
    // 写回TUN
    ssize_t written = write(tun_fd, response_packet, packet_len);
    if (written > 0 && g_log_level <= 0) { // 只在DEBUG级别记录
        CORE_LOGI("Wrote response to TUN: %s:%d -> %s:%d (%d bytes)", 
                 src_ip.c_str(), src_port, dst_ip.c_str(), dst_port, (int)written);
    }
}

// 设置socket为非阻塞
static bool set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags == -1) return false;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) != -1;
}

// 发送TCP SYN-ACK响应
static void sendTcpSynAck(int tun_fd, const std::string& src_ip, uint16_t src_port,
                         const std::string& dst_ip, uint16_t dst_port, 
                         uint32_t client_seq) {
    uint8_t response_packet[60]; // IP(20) + TCP(20) + 可选TCP选项(20)
    
    // IP头
    struct iphdr* ip_hdr = (struct iphdr*)response_packet;
    ip_hdr->version = 4;
    ip_hdr->ihl = 5;
    ip_hdr->tos = 0;
    ip_hdr->tot_len = htons(40); // IP(20) + TCP(20)
    ip_hdr->id = htons(rand() & 0xFFFF);
    ip_hdr->frag_off = 0;
    ip_hdr->ttl = 64;
    ip_hdr->protocol = IPPROTO_TCP;
    ip_hdr->check = 0;
    inet_aton(dst_ip.c_str(), (struct in_addr*)&ip_hdr->saddr); // 交换src/dst
    inet_aton(src_ip.c_str(), (struct in_addr*)&ip_hdr->daddr);
    
    // TCP头
    struct tcphdr* tcp_hdr = (struct tcphdr*)(response_packet + 20);
    tcp_hdr->source = htons(dst_port); // 交换端口
    tcp_hdr->dest = htons(src_port);
    tcp_hdr->seq = htonl(rand());
    tcp_hdr->ack_seq = htonl(client_seq + 1);
    tcp_hdr->doff = 5;
    tcp_hdr->fin = 0;
    tcp_hdr->syn = 1; // SYN
    tcp_hdr->rst = 0;
    tcp_hdr->psh = 0;
    tcp_hdr->ack = 1; // ACK
    tcp_hdr->urg = 0;
    tcp_hdr->window = htons(65535);
    tcp_hdr->check = 0;
    tcp_hdr->urg_ptr = 0;
    
    // 计算IP校验和
    uint32_t sum = 0;
    uint16_t* ip_words = (uint16_t*)ip_hdr;
    for (int i = 0; i < 10; i++) {
        sum += ntohs(ip_words[i]);
    }
    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    ip_hdr->check = htons(~sum);
    
    write(tun_fd, response_packet, 40);
    CORE_LOGI("Sent TCP SYN-ACK: %s:%d -> %s:%d", dst_ip.c_str(), dst_port, src_ip.c_str(), src_port);
}

// 解析IP数据包
static bool parse_ip_packet(const uint8_t* data, int len, 
                           std::string& src_ip, std::string& dst_ip, 
                           uint8_t& protocol, const uint8_t*& payload, int& payload_len) {
    if (len < 20) return false; // 最小IP头长度
    
    struct iphdr* ip = (struct iphdr*)data;
    if (ip->version != 4) return false; // 只处理IPv4
    
    int ip_header_len = ip->ihl * 4;
    if (len < ip_header_len) return false;
    
    struct in_addr src_addr = {static_cast<in_addr_t>(ip->saddr)};
    struct in_addr dst_addr = {static_cast<in_addr_t>(ip->daddr)};
    src_ip = inet_ntoa(src_addr);
    dst_ip = inet_ntoa(dst_addr);
    protocol = ip->protocol;
    payload = data + ip_header_len;
    payload_len = ntohs(ip->tot_len) - ip_header_len;
    
    return true;
}

// 处理TCP数据包
static void handle_tcp_packet(const std::string& src_ip, const std::string& dst_ip,
                              const uint8_t* tcp_data, int tcp_len) {
    if (tcp_len < 20) return; // 最小TCP头长度
    
    struct tcphdr* tcp = (struct tcphdr*)tcp_data;
    uint16_t src_port = ntohs(tcp->source);
    uint16_t dst_port = ntohs(tcp->dest);
    
    // 添加调试日志来追踪所有TCP包
    int tcp_header_len = tcp->doff * 4;
    int payload_len = tcp_len - tcp_header_len;
    if (payload_len > 0) {
        CORE_LOGI("TCP packet with payload: %s:%d -> %s:%d (%d bytes, flags: SYN=%d ACK=%d FIN=%d)", 
                 src_ip.c_str(), src_port, dst_ip.c_str(), dst_port, payload_len, tcp->syn, tcp->ack, tcp->fin);
    }    uint64_t key = make_session_key(src_ip, src_port, dst_ip, dst_port, IPPROTO_TCP);
    
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_tcp_sessions.find(key);
    
    if (tcp->syn && !tcp->ack) {
        // 新连接SYN包
        if (it != g_tcp_sessions.end()) {
            // 关闭旧连接
            if (it->second.socket_fd >= 0) {
                close(it->second.socket_fd);
            }
        }
        
        // 创建新socket连接到目标（或SOCKS代理）
        int sock_fd = socket(AF_INET, SOCK_STREAM, 0);
        if (sock_fd < 0) {
            CORE_LOGE("Failed to create socket: %s", strerror(errno));
            return;
        }
        
        set_nonblocking(sock_fd);
        
        // 连接到目标服务器：检查是否需要通过代理
        struct sockaddr_in addr = {};
        addr.sin_family = AF_INET;
        
        // 如果配置了SOCKS代理且目标是HTTPS(443端口)，通过代理连接
        if (!g_socks.empty() && dst_port == 443) {
            // 解析SOCKS代理地址
            size_t colon_pos = g_socks.find(':');
            if (colon_pos != std::string::npos) {
                std::string proxy_host = g_socks.substr(0, colon_pos);
                uint16_t proxy_port = static_cast<uint16_t>(std::stoi(g_socks.substr(colon_pos + 1)));
                
                addr.sin_port = htons(proxy_port);
                inet_aton(proxy_host.c_str(), &addr.sin_addr);
                
                if (g_log_level <= 1) {
                    CORE_LOGI("HTTPS connection via proxy: %s:%d -> %s:%d (via %s)", 
                             src_ip.c_str(), src_port, dst_ip.c_str(), dst_port, g_socks.c_str());
                }
            } else {
                // 代理配置错误，直连
                addr.sin_port = htons(dst_port);
                inet_aton(dst_ip.c_str(), &addr.sin_addr);
            }
        } else {
            // 直连到目标服务器
            addr.sin_port = htons(dst_port);
            inet_aton(dst_ip.c_str(), &addr.sin_addr);
        }
        
        TcpSession session;
        session.socket_fd = sock_fd;
        session.src_ip = src_ip;
        session.src_port = src_port;
        session.dst_ip = dst_ip;
        session.dst_port = dst_port;
        session.client_seq = ntohl(tcp->seq);
        session.state = STATE_CONNECTING;
        session.last_activity = std::chrono::steady_clock::now();
        
        g_tcp_sessions[key] = session;
        
        // 非阻塞connect
        int result = connect(sock_fd, (struct sockaddr*)&addr, sizeof(addr));
        CORE_LOGI("Connect to proxy result: %d, errno: %s", result, strerror(errno));
        if (result == 0 || errno == EINPROGRESS) {
            // 添加到epoll监控
            struct epoll_event ev = {};
            ev.events = EPOLLOUT | EPOLLIN | EPOLLET;
            ev.data.u64 = key;
            int epoll_result = epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, sock_fd, &ev);
            CORE_LOGI("Epoll add result: %d for socket %d, key: %llu", epoll_result, sock_fd, key);
            
            // 如果是通过代理的HTTPS连接，需要发送CONNECT请求
            if (!g_socks.empty() && dst_port == 443) {
                // 标记为需要发送CONNECT，但不立即发送SYN-ACK给客户端
                session.state = STATE_PROXY_CONNECT;
                g_tcp_sessions[key] = session;
                CORE_LOGI("Proxy connection initiated: %s:%d -> %s:%d", 
                         src_ip.c_str(), src_port, dst_ip.c_str(), dst_port);
            } else {
                // 立即发送SYN-ACK响应给客户端（非代理连接）
                sendTcpSynAck(g_tun_fd, src_ip, src_port, dst_ip, dst_port, session.client_seq);
                session.state = STATE_ESTABLISHED;
                g_tcp_sessions[key] = session;
            }
            
            if (g_log_level <= 1) {
                CORE_LOGI("New TCP connection: %s:%d -> %s:%d", 
                         src_ip.c_str(), src_port, dst_ip.c_str(), dst_port);
            }
        } else {
            CORE_LOGE("Connect failed: %s", strerror(errno));
            close(sock_fd);
            g_tcp_sessions.erase(key);
        }
    } else if (it != g_tcp_sessions.end()) {
        // 现有连接的数据包
        TcpSession& session = it->second;
        session.last_activity = std::chrono::steady_clock::now();
        
        CORE_LOGI("Found existing session: %s:%d -> %s:%d, state=%d", 
                 src_ip.c_str(), src_port, dst_ip.c_str(), dst_port, session.state);
        
        int tcp_header_len = tcp->doff * 4;
        const uint8_t* payload = tcp_data + tcp_header_len;
        int payload_len = tcp_len - tcp_header_len;
        
        if (payload_len > 0 && session.socket_fd >= 0) {
            if (session.state == STATE_ESTABLISHED) {
                CORE_LOGI("About to forward %d bytes from %s:%d to proxy", payload_len, src_ip.c_str(), src_port);
                // 转发payload到远端（直连或已建立的代理隧道）
                ssize_t sent = send(session.socket_fd, payload, payload_len, MSG_NOSIGNAL);
                if (sent > 0) {
                    CORE_LOGI("Successfully forwarded %d bytes to proxy", (int)sent);
                    if (g_cb) {
                        // 回调uplink数据 (方向0: 客户端->网络)
                        int callback_len = std::min(payload_len, 4096); // 限制回调数据大小
                        g_cb(0, IPPROTO_TCP, src_ip.c_str(), src_port, dst_ip.c_str(), dst_port, 
                             payload, callback_len);
                    }
                } else {
                    CORE_LOGE("Failed to forward data to proxy: %s", strerror(errno));
                }
                if (g_log_level <= 1) { // 改为INFO级别也显示
                    CORE_LOGI("Forwarded TCP data: %s:%d -> %s:%d (%d bytes)", 
                             src_ip.c_str(), src_port, dst_ip.c_str(), dst_port, (int)sent);
                }
            } else if (session.state == STATE_PROXY_CONNECT) {
                // 正在建立代理连接期间，缓存客户端数据
                CORE_LOGI("Caching TCP data during proxy connection: %d bytes", payload_len);
                std::vector<uint8_t> data(payload, payload + payload_len);
                session.pending_data.push_back(data);
            } else if (session.state == STATE_PROXY_RESPONSE) {
                // 等待代理响应期间，缓存客户端数据
                CORE_LOGI("Caching TCP data during proxy handshake: %d bytes", payload_len);
                std::vector<uint8_t> data(payload, payload + payload_len);
                session.pending_data.push_back(data);
            }
        }
        
        if (tcp->fin || tcp->rst) {
            // 连接关闭
            session.state = STATE_CLOSING;
        }
    }
}

// 处理UDP数据包
static void handle_udp_packet(const std::string& src_ip, const std::string& dst_ip,
                             const uint8_t* udp_data, int udp_len) {
    if (udp_len < 8) return; // 最小UDP头长度
    
    struct udphdr* udp = (struct udphdr*)udp_data;
    uint16_t src_port = ntohs(udp->source);
    uint16_t dst_port = ntohs(udp->dest);
    
    const uint8_t* payload = udp_data + 8;
    int payload_len = udp_len - 8;
    
    uint64_t key = make_session_key(src_ip, src_port, dst_ip, dst_port, IPPROTO_UDP);
    
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_udp_sessions.find(key);
    
    if (it == g_udp_sessions.end()) {
        // 新UDP会话
        int sock_fd = socket(AF_INET, SOCK_DGRAM, 0);
        if (sock_fd < 0) {
            CORE_LOGE("Failed to create UDP socket: %s", strerror(errno));
            return;
        }
        
        set_nonblocking(sock_fd);
        
        UdpSession session;
        session.socket_fd = sock_fd;
        session.src_ip = src_ip;
        session.src_port = src_port;
        session.dst_ip = dst_ip;
        session.dst_port = dst_port;
        session.last_activity = std::chrono::steady_clock::now();
        
        g_udp_sessions[key] = session;
        
        // 添加到epoll监控
        struct epoll_event ev = {};
        ev.events = EPOLLIN | EPOLLET;
        ev.data.u64 = key | (1ULL << 63); // 设置最高位标识UDP
        epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, sock_fd, &ev);
        
        if (g_log_level <= 1) {
            CORE_LOGI("New UDP session: %s:%d -> %s:%d", 
                     src_ip.c_str(), src_port, dst_ip.c_str(), dst_port);
        }
    }
    
    if (payload_len > 0) {
        auto& session = g_udp_sessions[key];
        session.last_activity = std::chrono::steady_clock::now();
        
        // 发送到目标
        struct sockaddr_in addr = {};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(dst_port);
        inet_aton(dst_ip.c_str(), &addr.sin_addr);
        
        ssize_t sent = sendto(session.socket_fd, payload, payload_len, MSG_NOSIGNAL,
                             (struct sockaddr*)&addr, sizeof(addr));
        if (sent > 0 && g_cb) {
            // 回调uplink数据
            int callback_len = std::min(payload_len, 4096);
            g_cb(0, IPPROTO_UDP, src_ip.c_str(), src_port, dst_ip.c_str(), dst_port,
                 payload, callback_len);
        }
    }
}

// 主事件循环
static void main_event_loop() {
    CORE_LOGI("Starting main event loop");
    
    // 创建epoll
    g_epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (g_epoll_fd < 0) {
        CORE_LOGE("Failed to create epoll: %s", strerror(errno));
        return;
    }
    
    // 添加TUN fd到epoll
    struct epoll_event ev = {};
    ev.events = EPOLLIN | EPOLLET;
    ev.data.fd = g_tun_fd;
    epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, g_tun_fd, &ev);
    
    std::vector<struct epoll_event> events(64);
    uint8_t buffer[4096];
    
    while (g_running.load()) {
        int nfds = epoll_wait(g_epoll_fd, events.data(), events.size(), 1000);
        
        for (int i = 0; i < nfds; i++) {
            if (events[i].data.fd == g_tun_fd) {
                // TUN接口数据
                ssize_t len = read(g_tun_fd, buffer, sizeof(buffer));
                if (len > 0) {
                    std::string src_ip, dst_ip;
                    uint8_t protocol;
                    const uint8_t* payload;
                    int payload_len;
                    
                    if (parse_ip_packet(buffer, len, src_ip, dst_ip, protocol, payload, payload_len)) {
                        if (protocol == IPPROTO_TCP) {
                            handle_tcp_packet(src_ip, dst_ip, payload, payload_len);
                        } else if (protocol == IPPROTO_UDP) {
                            handle_udp_packet(src_ip, dst_ip, payload, payload_len);
                        }
                    }
                }
            } else {
                // Socket数据
                uint64_t key = events[i].data.u64;
                bool is_udp = (key & (1ULL << 63)) != 0;
                key &= ~(1ULL << 63);
                
                if (is_udp) {
                    // UDP响应数据
                    std::lock_guard<std::mutex> lock(g_sessions_mutex);
                    auto it = g_udp_sessions.find(key);
                    if (it != g_udp_sessions.end()) {
                        ssize_t len = recv(it->second.socket_fd, buffer, sizeof(buffer), MSG_NOSIGNAL);
                        if (len > 0) {
                            if (g_cb) {
                                // 回调downlink数据 (方向1: 网络->客户端)
                                int callback_len = std::min((int)len, 4096);
                                g_cb(1, IPPROTO_UDP, it->second.dst_ip.c_str(), it->second.dst_port,
                                     it->second.src_ip.c_str(), it->second.src_port, buffer, callback_len);
                            }
                            // 构造UDP响应包写回TUN
                            writeResponseToTun(g_tun_fd, it->second.dst_ip, it->second.dst_port,
                                             it->second.src_ip, it->second.src_port, IPPROTO_UDP, buffer, len);
                        }
                    }
                } else {
                    // TCP响应数据
                    std::lock_guard<std::mutex> lock(g_sessions_mutex);
                    auto it = g_tcp_sessions.find(key);
                    if (it != g_tcp_sessions.end()) {
                        CORE_LOGI("TCP epoll event: fd=%d, events=0x%x, state=%d", 
                                 it->second.socket_fd, events[i].events, it->second.state);
                        if (events[i].events & EPOLLOUT) {
                            if (it->second.state == STATE_CONNECTING) {
                                // 连接真正建立（可选的二次确认）
                                it->second.state = STATE_ESTABLISHED;
                                // 发送SYN-ACK给客户端
                                sendTcpSynAck(g_tun_fd, it->second.src_ip, it->second.src_port,
                                            it->second.dst_ip, it->second.dst_port, it->second.client_seq);
                                CORE_LOGI("TCP connection fully established: %s:%d -> %s:%d",
                                         it->second.src_ip.c_str(), it->second.src_port,
                                         it->second.dst_ip.c_str(), it->second.dst_port);
                            } else if (it->second.state == STATE_PROXY_CONNECT) {
                                // 发送HTTP CONNECT请求到代理
                                std::string connect_req = "CONNECT " + it->second.dst_ip + ":" + 
                                                        std::to_string(it->second.dst_port) + " HTTP/1.1\r\n\r\n";
                                ssize_t sent = send(it->second.socket_fd, connect_req.c_str(), connect_req.length(), MSG_NOSIGNAL);
                                if (sent > 0) {
                                    it->second.state = STATE_PROXY_RESPONSE;
                                    CORE_LOGI("Sent CONNECT to proxy: %s:%d", it->second.dst_ip.c_str(), it->second.dst_port);
                                } else {
                                    CORE_LOGE("Failed to send CONNECT: %s", strerror(errno));
                                    close(it->second.socket_fd);
                                    g_tcp_sessions.erase(key);
                                }
                            }
                        }
                        if (events[i].events & EPOLLIN) {
                            if (it->second.state == STATE_PROXY_RESPONSE) {
                                // 处理代理的CONNECT响应
                                char response[512];
                                ssize_t len = recv(it->second.socket_fd, response, sizeof(response) - 1, MSG_NOSIGNAL);
                                if (len > 0) {
                                    response[len] = '\0';
                                    if (strstr(response, "200") != nullptr) {
                                        // 代理连接成功，切换到已建立状态
                                        it->second.state = STATE_ESTABLISHED;
                                        CORE_LOGI("Proxy CONNECT successful: %s:%d - Session now ESTABLISHED", it->second.dst_ip.c_str(), it->second.dst_port);
                                        
                                        // 现在发送SYN-ACK给客户端，告诉客户端连接已建立
                                        sendTcpSynAck(g_tun_fd, it->second.src_ip, it->second.src_port,
                                                    it->second.dst_ip, it->second.dst_port, it->second.client_seq);
                                        CORE_LOGI("Sent SYN-ACK after proxy tunnel established: %s:%d -> %s:%d", 
                                                 it->second.src_ip.c_str(), it->second.src_port,
                                                 it->second.dst_ip.c_str(), it->second.dst_port);
                                        
                                        // 转发所有缓存的数据
                                        for (const auto& data : it->second.pending_data) {
                                            ssize_t sent = send(it->second.socket_fd, data.data(), data.size(), MSG_NOSIGNAL);
                                            if (sent > 0) {
                                                CORE_LOGI("Forwarded cached data: %d bytes", (int)sent);
                                                if (g_cb) {
                                                    int callback_len = std::min((int)data.size(), 4096);
                                                    g_cb(0, IPPROTO_TCP, it->second.src_ip.c_str(), it->second.src_port,
                                                         it->second.dst_ip.c_str(), it->second.dst_port, data.data(), callback_len);
                                                }
                                            } else {
                                                CORE_LOGE("Failed to forward cached data: %s", strerror(errno));
                                            }
                                        }
                                        it->second.pending_data.clear(); // 清空缓存
                                    } else {
                                        CORE_LOGE("Proxy CONNECT failed: %s", response);
                                        close(it->second.socket_fd);
                                        g_tcp_sessions.erase(key);
                                    }
                                }
                            } else if (it->second.state == STATE_ESTABLISHED) {
                                ssize_t len = recv(it->second.socket_fd, buffer, sizeof(buffer), MSG_NOSIGNAL);
                                if (len > 0) {
                                    if (g_cb) {
                                        // 回调downlink数据
                                        int callback_len = std::min((int)len, 4096);
                                        g_cb(1, IPPROTO_TCP, it->second.dst_ip.c_str(), it->second.dst_port,
                                             it->second.src_ip.c_str(), it->second.src_port, buffer, callback_len);
                                    }
                                    // 构造TCP响应包写回TUN
                                    writeResponseToTun(g_tun_fd, it->second.dst_ip, it->second.dst_port,
                                                     it->second.src_ip, it->second.src_port, IPPROTO_TCP, buffer, len);
                                } else if (len == 0) {
                                    // 连接关闭
                                    CORE_LOGI("TCP connection closed by remote: %s:%d -> %s:%d",
                                             it->second.src_ip.c_str(), it->second.src_port,
                                             it->second.dst_ip.c_str(), it->second.dst_port);
                                    close(it->second.socket_fd);
                                    g_tcp_sessions.erase(key);
                                } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
                                    // 连接错误
                                    CORE_LOGE("TCP recv error: %s", strerror(errno));
                                    close(it->second.socket_fd);
                                    g_tcp_sessions.erase(key);
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // 清理超时会话
        cleanup_expired_sessions();
    }
    
    close(g_epoll_fd);
    g_epoll_fd = -1;
}

extern "C" int tt_init(int tun_fd, const char* socks_server, const char* dns_server, int mtu) {
    g_tun_fd = tun_fd;
    g_mtu = mtu;
    g_socks = socks_server ? socks_server : "";
    g_dns = dns_server ? dns_server : "";
    CORE_LOGI("tt_init tun_fd=%d mtu=%d socks=%s dns=%s", tun_fd, mtu, g_socks.c_str(), g_dns.c_str());
    return 0;
}

extern "C" int tt_start() {
    if (g_running.exchange(true)) return 0;
    CORE_LOGI("tt_start - launching real forwarding engine");
    
    // 启动主事件循环线程
    std::thread(main_event_loop).detach();
    
    return 0;
}

extern "C" void tt_stop() {
    CORE_LOGI("tt_stop");
    g_running.store(false);
    
    // 清理所有会话
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    for (auto& pair : g_tcp_sessions) {
        if (pair.second.socket_fd >= 0) {
            close(pair.second.socket_fd);
        }
    }
    for (auto& pair : g_udp_sessions) {
        if (pair.second.socket_fd >= 0) {
            close(pair.second.socket_fd);
        }
    }
    g_tcp_sessions.clear();
    g_udp_sessions.clear();
}

extern "C" void tt_set_log_level(int level) {
    g_log_level = level;
    CORE_LOGI("tt_set_log_level level=%d", level);
}

extern "C" const char* tt_version() {
    return "real-core-1.0";
}

extern "C" void tt_register_callback(void (*cb)(int,int,const char*,int,const char*,int,const uint8_t*,int)) {
    g_cb = cb;
    CORE_LOGI("tt_register_callback set=%p", (void*)cb);
}
