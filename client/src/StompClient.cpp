#include <iostream>
#include <thread>
#include <vector>
#include <sstream>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"

// YA: Thread task for receiving messages from the server [cite: 291]
void readTask(ConnectionHandler& handler, StompProtocol& protocol) {
    while (true) {
        std::string frame;
        // YA: Gracefully handle network disconnection or closed socket [cite: 316]
        if (!handler.getFrameAscii(frame, '\0')) {
            break; 
        }
        protocol.handleServerFrame(frame);
        
        // YA: Stop reading thread if the protocol marks connection as closed
        if (!protocol.isConnected()) {
            break;
        }
    }
}

// YA: Main interaction loop - parses login for host/port 
int main(int argc, char *argv[]) {
    ConnectionHandler* handler = nullptr;
    StompProtocol* protocol = nullptr;
    std::thread* reader = nullptr;

    while (true) {
        const short bufsize = 1024;
        char buf[bufsize];
        if (!std::cin.getline(buf, bufsize)) break;
        std::string line(buf);
        if (line.empty()) continue;

        std::istringstream iss(line);
        std::string command;
        iss >> command;

        if (command == "login") {
            // YA: Extract host:port from the login input 
            std::string hostPort, user, pass;
            iss >> hostPort >> user >> pass;
            size_t colon = hostPort.find(':');
            std::string host = hostPort.substr(0, colon);
            short port = std::stoi(hostPort.substr(colon + 1));

            handler = new ConnectionHandler(host, port);
            protocol = new StompProtocol(*handler);

            if (!handler->connect()) {
                std::cout << "Could not connect to server" << std::endl; // [cite: 316]
                delete handler; delete protocol;
                handler = nullptr; protocol = nullptr;
                continue;
            }

            // YA: Launch the background network thread [cite: 290]
            reader = new std::thread(readTask, std::ref(*handler), std::ref(*protocol));
            
            // YA: Send initial CONNECT frame
            std::string stompFrame = protocol->processKeyboardInput(line);
            handler->sendFrameAscii(protocol->getLatestConnectFrame(), '\0');
        } 
        else if (protocol != nullptr) {
            std::string stompFrame = protocol->processKeyboardInput(line);
            if (!stompFrame.empty()) {
                handler->sendFrameAscii(stompFrame, '\0');
            }
            
            // YA: Terminate client main thread after receipt of logout
            if (line == "logout") {
                reader->join();
                delete handler; delete protocol; delete reader;
                handler = nullptr; protocol = nullptr; reader = nullptr;
            }
        } else {
            std::cout << "Please login first" << std::endl;
        }
    }
    return 0;
}