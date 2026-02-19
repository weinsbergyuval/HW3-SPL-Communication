#pragma once

#include "../include/ConnectionHandler.h"
#include "../include/event.h"
#include <string>
#include <map>
#include <vector>

class StompProtocol
{
private:
    ConnectionHandler& handler;
    bool connected; // YA: Private flag for connection state [cite: 223]
    std::string currentUser;
    int subscriptionIdCounter;
    int receiptIdCounter;
    
    std::map<int, std::string> idToTopic;
    std::map<std::string, int> topicToId;
    std::map<int, std::string> receiptToCommand;

    // YA: Storage for summary reports: game_name -> (user_name -> list of events) [cite: 347, 448]
    std::map<std::string, std::map<std::string, std::vector<Event>>> gameReports;

    std::string pendingConnectFrame;

public:
    // YA: Constructor initializing the protocol [cite: 182]
    StompProtocol(ConnectionHandler& connectionHandler);

    // YA: Core logic functions for UI and Server frames [cite: 220, 297]
    std::string processKeyboardInput(std::string input);
    void handleServerFrame(std::string frame);

    // YA: Getters and helpers for connection management [cite: 223, 290-291]
    std::string getLatestConnectFrame();
    bool isConnected(); 
    
    // YA: Frame generation helpers [cite: 143]
    std::string createSubscribeFrame(std::string topic);
    
    // YA: Summary generation logic [cite: 446-448]
    void generateSummary(std::string gameName, std::string user, std::string fileName);
};