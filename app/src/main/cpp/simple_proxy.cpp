// Simple transparent proxy implementation based on successful open source projects
// Focus: Reliable packet forwarding first, then add advanced features

#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <unordered_map>
#include <mutex>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <errno.h>
#include <android/log.h>
#include <chrono>
#include <vector>
#include <cstring>

#define LOG_TAG "SimpleProxy"
#define SIMPLE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define SIMPLE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state
static int g_tun_fd = -1;
static int g_epoll_fd = -1;
static std::atomic<bool> g_running{false};
static std::thread g_worker_thread;
static JavaVM* g_java_vm = nullptr;
static jobject g_protect_callback = nullptr;

// Simple session structure
struct Session {
    int socket_fd;
    std::string src_ip;
    uint16_t src_port;
    std::string dst_ip; 
    uint16_t dst_port;
    uint8_t protocol;
    std::chrono::steady_clock::time_point last_activity;
};

static std::unordered_map<uint64_t, Session> g_sessions;
static std::mutex g_sessions_mutex;

// Utility functions
static void set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static bool protect_socket(int fd) {
    if (!g_protect_callback || !g_java_vm) {
        SIMPLE_LOGI("No protect callback available for fd=%d", fd);
        return true; // Assume success if no callback
    }
    
    JNIEnv* env = nullptr;
    bool did_attach = false;
    
    int result = g_java_vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        result = g_java_vm->AttachCurrentThread(&env, nullptr);
        if (result != JNI_OK) {
            SIMPLE_LOGE("Failed to attach thread for protect callback");
            return false;
        }
        did_attach = true;
    }
    
    if (env) {
        jclass callback_class = env->GetObjectClass(g_protect_callback);
        jmethodID protect_method = env->GetMethodID(callback_class, "protect", "(I)Z");
        
        if (protect_method) {
            jboolean protected_result = env->CallBooleanMethod(g_protect_callback, protect_method, fd);
            SIMPLE_LOGI("Protected socket fd=%d, result=%d", fd, protected_result);
            
            if (did_attach) {
                g_java_vm->DetachCurrentThread();
            }
            return protected_result;
        }
        
        if (did_attach) {
            g_java_vm->DetachCurrentThread();
        }
    }
    
    SIMPLE_LOGE("Failed to call protect callback for fd=%d", fd);
    return false;
}

static uint64_t make_session_key(const std::string& src_ip, uint16_t src_port, 
                                const std::string& dst_ip, uint16_t dst_port, uint8_t protocol) {
    // Simple hash combination - fix for 64-bit safety
    uint64_t key = 0;
    key ^= static_cast<uint64_t>(std::hash<std::string>{}(src_ip)) << 16;
    key ^= static_cast<uint64_t>(std::hash<std::string>{}(dst_ip)) << 24;
    key ^= (static_cast<uint64_t>(src_port) << 48);
    key ^= (static_cast<uint64_t>(dst_port) << 8);
    key ^= protocol;
    return key;
}

// Parse IP packet
static bool parse_ip_packet(const uint8_t* data, int len, 
                           std::string& src_ip, std::string& dst_ip, 
                           uint8_t& protocol, const uint8_t*& payload, int& payload_len) {
    if (len < 20) return false;
    
    struct iphdr* ip = (struct iphdr*)data;
    if (ip->version != 4) return false;
    
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

// Simple direct forwarding - no complex proxy logic
static void send_tcp_syn_ack(int tun_fd, const std::string& src_ip, uint16_t src_port,
                            const std::string& dst_ip, uint16_t dst_port, uint32_t seq, uint32_t ack_seq) {
    const int ip_header_len = 20;
    const int tcp_header_len = 20;
    const int total_len = ip_header_len + tcp_header_len;
    
    uint8_t packet[64];
    memset(packet, 0, sizeof(packet));
    
    // IP header
    struct iphdr* ip = (struct iphdr*)packet;
    ip->version = 4;
    ip->ihl = 5;
    ip->tos = 0;
    ip->tot_len = htons(total_len);
    ip->id = htons(12347);
    ip->frag_off = 0;
    ip->ttl = 64;
    ip->protocol = IPPROTO_TCP;
    ip->check = 0;
    inet_aton(dst_ip.c_str(), (struct in_addr*)&ip->saddr); // dst becomes src
    inet_aton(src_ip.c_str(), (struct in_addr*)&ip->daddr); // src becomes dst
    
    // Calculate IP checksum
    uint32_t sum = 0;
    uint16_t* ptr = (uint16_t*)ip;
    for (int i = 0; i < ip_header_len / 2; i++) {
        sum += ntohs(ptr[i]);
    }
    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    ip->check = htons(~sum);
    
    // TCP header
    struct tcphdr* tcp = (struct tcphdr*)(packet + ip_header_len);
    tcp->source = htons(dst_port); // dst becomes src
    tcp->dest = htons(src_port);   // src becomes dst
    tcp->seq = htonl(seq);
    tcp->ack_seq = htonl(ack_seq);
    tcp->doff = tcp_header_len / 4;
    tcp->syn = 1;
    tcp->ack = 1;
    tcp->window = htons(65535);
    tcp->check = 0;
    tcp->urg_ptr = 0;
    
    ssize_t written = write(tun_fd, packet, total_len);
    if (written > 0) {
        SIMPLE_LOGI("Sent TCP SYN-ACK: %s:%d -> %s:%d", 
                   dst_ip.c_str(), dst_port, src_ip.c_str(), src_port);
    }
}

static void handle_tcp_packet(const std::string& src_ip, const std::string& dst_ip, 
                             const uint8_t* tcp_data, int tcp_len) {
    if (tcp_len < 20) return;
    
    struct tcphdr* tcp = (struct tcphdr*)tcp_data;
    uint16_t src_port = ntohs(tcp->source);
    uint16_t dst_port = ntohs(tcp->dest);
    uint32_t seq = ntohl(tcp->seq);
    uint32_t ack_seq = ntohl(tcp->ack_seq);
    
    // Use VPN internal IP as source for session lookup
    std::string vpn_src_ip = "10.0.0.2";
    uint64_t key = make_session_key(vpn_src_ip, src_port, dst_ip, dst_port, IPPROTO_TCP);
    
    SIMPLE_LOGI("TCP packet: %s:%d -> %s:%d (SYN=%d, ACK=%d, seq=%u)", 
               src_ip.c_str(), src_port, dst_ip.c_str(), dst_port, tcp->syn, tcp->ack, seq);
    
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(key);
    
    if (it == g_sessions.end() && tcp->syn && !tcp->ack) {
        // Create new session for SYN packet
        int sock_fd = socket(AF_INET, SOCK_STREAM, 0);
        if (sock_fd < 0) {
            SIMPLE_LOGE("Failed to create TCP socket: %s", strerror(errno));
            return;
        }
        
        // Protect socket from VPN routing
        if (!protect_socket(sock_fd)) {
            SIMPLE_LOGE("Failed to protect TCP socket");
            close(sock_fd);
            return;
        }
        
        set_nonblocking(sock_fd);
        
        // Connect directly to destination
        struct sockaddr_in addr = {};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(dst_port);
        inet_aton(dst_ip.c_str(), &addr.sin_addr);
        
        int result = connect(sock_fd, (struct sockaddr*)&addr, sizeof(addr));
        if (result == 0 || errno == EINPROGRESS) {
            // Store session
            Session session;
            session.socket_fd = sock_fd;
            session.src_ip = src_ip;
            session.src_port = src_port;
            session.dst_ip = dst_ip;
            session.dst_port = dst_port;
            session.protocol = IPPROTO_TCP;
            session.last_activity = std::chrono::steady_clock::now();
            g_sessions[key] = session;
            
            // Add to epoll
            struct epoll_event ev = {};
            ev.events = EPOLLIN | EPOLLOUT | EPOLLET;
            ev.data.u64 = key;
            epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, sock_fd, &ev);
            
            // Send SYN-ACK response to client
            send_tcp_syn_ack(g_tun_fd, src_ip, src_port, dst_ip, dst_port, 1000, seq + 1);
            
            SIMPLE_LOGI("Created TCP session: %s:%d -> %s:%d", 
                       src_ip.c_str(), src_port, dst_ip.c_str(), dst_port);
        } else {
            SIMPLE_LOGE("TCP connect failed: %s", strerror(errno));
            close(sock_fd);
        }
    } else if (it != g_sessions.end() && tcp->ack && !tcp->syn) {
        // Handle ACK packets (including data)
        const uint8_t* payload = tcp_data + (tcp->doff * 4);
        int payload_len = tcp_len - (tcp->doff * 4);
        
        if (payload_len > 0) {
            // Forward data to remote server
            ssize_t sent = send(it->second.socket_fd, payload, payload_len, 0);
            if (sent > 0) {
                SIMPLE_LOGI("Forwarded TCP data: %d bytes to %s:%d", 
                           (int)sent, dst_ip.c_str(), dst_port);
                it->second.last_activity = std::chrono::steady_clock::now();
            }
        }
    }
}

static void handle_udp_packet(const std::string& src_ip, const std::string& dst_ip, 
                             const uint8_t* udp_data, int udp_len) {
    if (udp_len < 8) return;
    
    struct udphdr* udp = (struct udphdr*)udp_data;
    uint16_t src_port = ntohs(udp->source);
    uint16_t dst_port = ntohs(udp->dest);
    
    const uint8_t* payload = udp_data + 8;
    int payload_len = udp_len - 8;
    
    // Use VPN internal IP as source for session lookup
    std::string vpn_src_ip = "10.0.0.2";
    uint64_t key = make_session_key(vpn_src_ip, src_port, dst_ip, dst_port, IPPROTO_UDP);
    
    SIMPLE_LOGI("UDP packet: %s:%d -> %s:%d (%d bytes)", 
               src_ip.c_str(), src_port, dst_ip.c_str(), dst_port, payload_len);
    
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(key);
    
    if (it == g_sessions.end()) {
        // Create new UDP session
        int sock_fd = socket(AF_INET, SOCK_DGRAM, 0);
        if (sock_fd < 0) {
            SIMPLE_LOGE("Failed to create UDP socket: %s", strerror(errno));
            return;
        }
        
        // Protect socket from VPN routing
        if (!protect_socket(sock_fd)) {
            SIMPLE_LOGE("Failed to protect UDP socket");
            close(sock_fd);
            return;
        }
        
        set_nonblocking(sock_fd);
        
        // Store session
        Session session;
        session.socket_fd = sock_fd;
        session.src_ip = src_ip;
        session.src_port = src_port;
        session.dst_ip = dst_ip;
        session.dst_port = dst_port;
        session.protocol = IPPROTO_UDP;
        session.last_activity = std::chrono::steady_clock::now();
        g_sessions[key] = session;
        
        // Add to epoll
        struct epoll_event ev = {};
        ev.events = EPOLLIN | EPOLLET;
        ev.data.u64 = key | (1ULL << 63); // Mark as UDP
        epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, sock_fd, &ev);
        
        SIMPLE_LOGI("Created UDP session: %s:%d -> %s:%d", 
                   src_ip.c_str(), src_port, dst_ip.c_str(), dst_port);
        
        it = g_sessions.find(key);
    }
    
    if (it != g_sessions.end() && payload_len > 0) {
        // Forward UDP packet
        struct sockaddr_in addr = {};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(dst_port);
        inet_aton(dst_ip.c_str(), &addr.sin_addr);
        
        ssize_t sent = sendto(it->second.socket_fd, payload, payload_len, 0,
                             (struct sockaddr*)&addr, sizeof(addr));
        if (sent > 0) {
            SIMPLE_LOGI("Forwarded UDP: %d bytes", (int)sent);
            it->second.last_activity = std::chrono::steady_clock::now();
        } else {
            SIMPLE_LOGE("Failed to forward UDP: %s", strerror(errno));
        }
    }
}

// Write response back to TUN interface
static void write_response_to_tun(int tun_fd, const std::string& src_ip, uint16_t src_port,
                                 const std::string& dst_ip, uint16_t dst_port, 
                                 uint8_t protocol, const uint8_t* data, int data_len) {
    if (protocol == IPPROTO_UDP) {
        // Construct UDP response packet
        const int ip_header_len = 20;
        const int udp_header_len = 8;
        const int total_len = ip_header_len + udp_header_len + data_len;
        
        uint8_t packet[2048];
        if (total_len > sizeof(packet)) {
            SIMPLE_LOGE("Packet too large: %d bytes", total_len);
            return;
        }
        
        // IP header
        struct iphdr* ip = (struct iphdr*)packet;
        memset(ip, 0, ip_header_len);
        ip->version = 4;
        ip->ihl = 5;
        ip->tos = 0;
        ip->tot_len = htons(total_len);
        ip->id = htons(12345);
        ip->frag_off = 0;
        ip->ttl = 64;
        ip->protocol = IPPROTO_UDP;
        ip->check = 0;
        inet_aton(src_ip.c_str(), (struct in_addr*)&ip->saddr);
        inet_aton(dst_ip.c_str(), (struct in_addr*)&ip->daddr);
        
        // Calculate IP checksum
        uint32_t sum = 0;
        uint16_t* ptr = (uint16_t*)ip;
        for (int i = 0; i < ip_header_len / 2; i++) {
            sum += ntohs(ptr[i]);
        }
        while (sum >> 16) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        ip->check = htons(~sum);
        
        // UDP header
        struct udphdr* udp = (struct udphdr*)(packet + ip_header_len);
        udp->source = htons(src_port);
        udp->dest = htons(dst_port);
        udp->len = htons(udp_header_len + data_len);
        udp->check = 0; // Skip UDP checksum for simplicity
        
        // Copy payload
        memcpy(packet + ip_header_len + udp_header_len, data, data_len);
        
        // Write to TUN
        ssize_t written = write(tun_fd, packet, total_len);
        if (written > 0) {
            SIMPLE_LOGI("Wrote UDP response to TUN: %d bytes (%s:%d -> %s:%d)", 
                       (int)written, src_ip.c_str(), src_port, dst_ip.c_str(), dst_port);
        } else {
            SIMPLE_LOGE("Failed to write UDP response to TUN: %s", strerror(errno));
        }
    } else if (protocol == IPPROTO_TCP) {
        // Construct TCP response packet
        const int ip_header_len = 20;
        const int tcp_header_len = 20; // Basic TCP header without options
        const int total_len = ip_header_len + tcp_header_len + data_len;
        
        uint8_t packet[2048];
        if (total_len > sizeof(packet)) {
            SIMPLE_LOGE("TCP packet too large: %d bytes", total_len);
            return;
        }
        
        // IP header
        struct iphdr* ip = (struct iphdr*)packet;
        memset(ip, 0, ip_header_len);
        ip->version = 4;
        ip->ihl = 5;
        ip->tos = 0;
        ip->tot_len = htons(total_len);
        ip->id = htons(12346); // Different ID for TCP
        ip->frag_off = 0;
        ip->ttl = 64;
        ip->protocol = IPPROTO_TCP;
        ip->check = 0;
        inet_aton(src_ip.c_str(), (struct in_addr*)&ip->saddr);
        inet_aton(dst_ip.c_str(), (struct in_addr*)&ip->daddr);
        
        // Calculate IP checksum
        uint32_t sum = 0;
        uint16_t* ptr = (uint16_t*)ip;
        for (int i = 0; i < ip_header_len / 2; i++) {
            sum += ntohs(ptr[i]);
        }
        while (sum >> 16) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        ip->check = htons(~sum);
        
        // TCP header
        struct tcphdr* tcp = (struct tcphdr*)(packet + ip_header_len);
        memset(tcp, 0, tcp_header_len);
        tcp->source = htons(src_port);
        tcp->dest = htons(dst_port);
        tcp->seq = htonl(1000); // Simplified sequence number
        tcp->ack_seq = htonl(2000); // Simplified ack number
        tcp->doff = tcp_header_len / 4; // Data offset (header length in 32-bit words)
        tcp->ack = 1; // ACK flag
        tcp->psh = 1; // PUSH flag for data
        tcp->window = htons(65535); // Window size
        tcp->check = 0; // Skip TCP checksum for simplicity
        tcp->urg_ptr = 0;
        
        // Copy payload
        memcpy(packet + ip_header_len + tcp_header_len, data, data_len);
        
        // Write to TUN
        ssize_t written = write(tun_fd, packet, total_len);
        if (written > 0) {
            SIMPLE_LOGI("Wrote TCP response to TUN: %d bytes (%s:%d -> %s:%d)", 
                       (int)written, src_ip.c_str(), src_port, dst_ip.c_str(), dst_port);
        } else {
            SIMPLE_LOGE("Failed to write TCP response to TUN: %s", strerror(errno));
        }
    }
}

// Main event loop
static void main_event_loop() {
    SIMPLE_LOGI("Starting simple proxy event loop");
    
    g_epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (g_epoll_fd < 0) {
        SIMPLE_LOGE("Failed to create epoll: %s", strerror(errno));
        return;
    }
    
    // Add TUN fd to epoll
    struct epoll_event ev = {};
    ev.events = EPOLLIN | EPOLLET;
    ev.data.fd = g_tun_fd;
    epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, g_tun_fd, &ev);
    
    std::vector<struct epoll_event> events(64);
    uint8_t buffer[4096];
    
    // Add status logging
    auto last_status_time = std::chrono::steady_clock::now();
    
    while (g_running.load()) {
        int nfds = epoll_wait(g_epoll_fd, events.data(), events.size(), 1000);
        
        // Print status every 10 seconds
        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::seconds>(now - last_status_time).count() >= 10) {
            SIMPLE_LOGI("Event loop running - TUN fd=%d, sessions=%zu", g_tun_fd, g_sessions.size());
            last_status_time = now;
        }
        
        for (int i = 0; i < nfds; i++) {
            if (events[i].data.fd == g_tun_fd) {
                // TUN interface data
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
                // Socket response data
                uint64_t key = events[i].data.u64;
                bool is_udp = (key & (1ULL << 63)) != 0;
                if (is_udp) key &= ~(1ULL << 63);
                
                std::lock_guard<std::mutex> lock(g_sessions_mutex);
                auto it = g_sessions.find(key);
                if (it != g_sessions.end()) {
                    ssize_t len = recv(it->second.socket_fd, buffer, sizeof(buffer), 0);
                    if (len > 0) {
                        SIMPLE_LOGI("Received response: %d bytes from %s:%d", 
                                   (int)len, it->second.dst_ip.c_str(), it->second.dst_port);
                        
                        // Write back to TUN (simplified)
                        write_response_to_tun(g_tun_fd, it->second.dst_ip, it->second.dst_port,
                                             it->second.src_ip, it->second.src_port,
                                             it->second.protocol, buffer, len);
                        
                        it->second.last_activity = std::chrono::steady_clock::now();
                    } else if (len == 0) {
                        // Connection closed
                        SIMPLE_LOGI("Connection closed: %s:%d", 
                                   it->second.dst_ip.c_str(), it->second.dst_port);
                        close(it->second.socket_fd);
                        g_sessions.erase(key);
                    }
                }
            }
        }
    }
    
    close(g_epoll_fd);
    SIMPLE_LOGI("Event loop stopped");
}

// JNI exports
extern "C" {

JNIEXPORT jstring JNICALL
Java_com_trafficcapture_tun2socks_SimpleTun2socks_getVersion(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF("simple-proxy-1.0");
}

JNIEXPORT jint JNICALL
Java_com_trafficcapture_tun2socks_SimpleTun2socks_init(JNIEnv *env, jclass clazz, 
                                                      jint tun_fd, jstring socks_server, 
                                                      jstring dns_server, jint mtu) {
    g_tun_fd = tun_fd;
    SIMPLE_LOGI("Simple proxy init: tun_fd=%d", tun_fd);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_trafficcapture_tun2socks_SimpleTun2socks_start(JNIEnv *env, jclass clazz) {
    if (g_running.load()) {
        SIMPLE_LOGI("Already running");
        return 0;
    }
    
    g_running.store(true);
    g_worker_thread = std::thread(main_event_loop);
    SIMPLE_LOGI("Simple proxy started");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_trafficcapture_tun2socks_SimpleTun2socks_stop(JNIEnv *env, jclass clazz) {
    if (!g_running.load()) {
        return 0;
    }
    
    g_running.store(false);
    if (g_worker_thread.joinable()) {
        g_worker_thread.join();
    }
    
    // Clean up sessions
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    for (auto& pair : g_sessions) {
        close(pair.second.socket_fd);
    }
    g_sessions.clear();
    
    SIMPLE_LOGI("Simple proxy stopped");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_trafficcapture_tun2socks_SimpleTun2socks_setProtectCallback(JNIEnv *env, jclass clazz, jobject callback) {
    // Get JavaVM for later use
    if (env->GetJavaVM(&g_java_vm) != JNI_OK) {
        SIMPLE_LOGE("Failed to get JavaVM");
        return -1;
    }
    
    // Store protect callback as global reference
    if (callback) {
        if (g_protect_callback) {
            env->DeleteGlobalRef(g_protect_callback);
        }
        g_protect_callback = env->NewGlobalRef(callback);
        SIMPLE_LOGI("Protect callback set successfully");
        return 0;
    }
    return -1;
}

} // extern "C"
