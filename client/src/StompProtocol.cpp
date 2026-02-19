#include "../include/StompProtocol.h"
#include "../include/event.h"
#include <sstream>
#include <iostream>
#include <fstream>
#include <algorithm>
#include <map>

// YA: Initialize protocol state and counters [cite: 182]
StompProtocol::StompProtocol(ConnectionHandler& connectionHandler) 
    : handler(connectionHandler), connected(false), currentUser(""), 
      subscriptionIdCounter(0), receiptIdCounter(0), idToTopic(), topicToId(), 
      receiptToCommand(), gameReports(), pendingConnectFrame("") {}

// YA: Process keyboard commands and translate them to STOMP frames [cite: 297]
std::string StompProtocol::processKeyboardInput(std::string input) {
    std::istringstream iss(input);
    std::string command;
    iss >> command;

    if (command == "login") {
        std::string hostPort, username, password;
        iss >> hostPort >> username >> password;

        // YA: Test 2 Case A - Prevent login if already connected locally [cite: 317, 622]
        if (connected) {
            std::cout << "The client is already logged in, log out before trying again" << std::endl;
            return "";
        }

        this->currentUser = username; 
        
        // YA: Construct CONNECT frame [cite: 52-56]
        std::string frame = "CONNECT\n";
        frame += "accept-version:1.2\n";
        frame += "host:stomp.cs.bgu.ac.il\n";
        frame += "login:" + username + "\n";
        frame += "passcode:" + password + "\n";
        frame += "\n";

        this->pendingConnectFrame = frame;
        return "CONNECT_PENDING"; 
    }

    // YA: Requirement 3.4.1 - Ensure user is logged in for other commands [cite: 304]
    if (!connected) {
        std::cout << "Please login first" << std::endl;
        return "";
    }

    if (command == "join") {
        std::string gameName;
        iss >> gameName;
        return createSubscribeFrame(gameName);
    }

    if (command == "exit") {
        std::string gameName;
        iss >> gameName;
        if (topicToId.count(gameName)) {
            int subId = topicToId[gameName];
            int rId = receiptIdCounter++;
            receiptToCommand[rId] = "exit " + gameName;
            
            // YA: Construct UNSUBSCRIBE frame with receipt [cite: 158, 360]
            std::string frame = "UNSUBSCRIBE\n";
            frame += "id:" + std::to_string(subId) + "\n";
            frame += "receipt:" + std::to_string(rId) + "\n\n";
            return frame;
        }
        return "";
    }

    if (command == "report") {
        std::string path;
        iss >> path;
        // YA: Parse events from JSON file [cite: 514]
        names_and_events parsedData = parseEventsFile(path);
        std::string topic = parsedData.team_a_name + "_" + parsedData.team_b_name;

        for (const Event& e : parsedData.events) {
            // YA: Build SEND frame with 4-space indentation [cite: 382, 404]
            std::string frame = "SEND\n";
            frame += "destination:/" + topic + "\n\n";
            frame += "user: " + currentUser + "\n";
            frame += "team a: " + e.get_team_a_name() + "\n";
            frame += "team b: " + e.get_team_b_name() + "\n";
            frame += "event name: " + e.get_name() + "\n";
            frame += "time: " + std::to_string(e.get_time()) + "\n";
            
            frame += "general game updates:\n";
            for (auto const& [key, val] : e.get_game_updates()) {
                frame += "    " + key + ": " + val + "\n";
            }
            frame += "team a updates:\n";
            for (auto const& [key, val] : e.get_team_a_updates()) {
                frame += "    " + key + ": " + val + "\n";
            }
            frame += "team b updates:\n";
            for (auto const& [key, val] : e.get_team_b_updates()) {
                frame += "    " + key + ": " + val + "\n";
            }
            frame += "description:\n" + e.get_discription() + "\n";
            
            handler.sendFrameAscii(frame, '\0');
        }
        return "";
    }

    if (command == "summary") {
        std::string gameName, user, fileName;
        iss >> gameName >> user >> fileName;
        generateSummary(gameName, user, fileName);
        return "";
    }

    if (command == "logout") {
        int rId = receiptIdCounter++;
        receiptToCommand[rId] = "logout";
        // YA: Construct DISCONNECT frame and wait for receipt [cite: 164, 476]
        std::string frame = "DISCONNECT\n";
        frame += "receipt:" + std::to_string(rId) + "\n\n"; 
        return frame;
    }

    return "";
}

// YA: Handle incoming frames from the server [cite: 21]
void StompProtocol::handleServerFrame(std::string frame) {
    std::istringstream iss(frame);
    std::string command;
    std::getline(iss, command);

    if (command == "CONNECTED") {
        connected = true;
        std::cout << "Login successful" << std::endl; // [cite: 322]
    }
    else if (command == "RECEIPT") {
        std::string line;
        while (std::getline(iss, line) && line != "") {
            if (line.find("receipt-id:") == 0) {
                int rId = std::stoi(line.substr(11));
                if (receiptToCommand[rId] == "logout") {
                    connected = false;
                    handler.close(); // YA: Close socket after logout receipt [cite: 175, 479]
                    std::cout << "Logged out successfully" << std::endl;
                }
                else if (receiptToCommand[rId].find("join") == 0) {
                    std::cout << "Joined channel " << receiptToCommand[rId].substr(5) << std::endl; // [cite: 344]
                }
                else if (receiptToCommand[rId].find("exit") == 0) {
                    std::cout << "Exited channel " << receiptToCommand[rId].substr(5) << std::endl; // [cite: 362]
                }
            }
        }
    }
    else if (command == "MESSAGE") {
        // YA: Store received events for summary [cite: 347, 448]
        std::string line, topic, user, teamA, teamB, eventName, bodyLine;
        int time = 0;
        std::map<std::string, std::string> gen, tA, tB;
        std::string desc;

        while (std::getline(iss, line) && line != "") {
            if (line.find("destination:/") == 0) topic = line.substr(13);
        }

        std::string current = "";
        while (std::getline(iss, bodyLine)) {
            if (bodyLine.find("user: ") == 0) user = bodyLine.substr(6);
            else if (bodyLine.find("team a: ") == 0) teamA = bodyLine.substr(8);
            else if (bodyLine.find("team b: ") == 0) teamB = bodyLine.substr(8);
            else if (bodyLine.find("event name: ") == 0) eventName = bodyLine.substr(12);
            else if (bodyLine.find("time: ") == 0) time = std::stoi(bodyLine.substr(6));
            else if (bodyLine.find("general game updates:") == 0) current = "gen";
            else if (bodyLine.find("team a updates:") == 0) current = "tA";
            else if (bodyLine.find("team b updates:") == 0) current = "tB";
            else if (bodyLine.find("description:") == 0) current = "desc";
            else if (bodyLine.substr(0, 4) == "    ") {
                size_t colon = bodyLine.find(": ");
                if (colon != std::string::npos) {
                    std::string k = bodyLine.substr(4, colon - 4);
                    std::string v = bodyLine.substr(colon + 2);
                    if (current == "gen") gen[k] = v;
                    else if (current == "tA") tA[k] = v;
                    else if (current == "tB") tB[k] = v;
                }
            } else if (current == "desc") desc += bodyLine + "\n";
        }
        Event receivedEvent(teamA, teamB, eventName, time, gen, tA, tB, desc);
        gameReports[topic][user].push_back(receivedEvent);
    }
    else if (command == "ERROR") {
        // YA: Extract error message and handle Test 2 Case B [cite: 305, 636]
        std::string line, errorMsg;
        while (std::getline(iss, line) && line != "") {
            if (line.find("message:") == 0) errorMsg = line.substr(8);
        }
        
        if (errorMsg.find("User already logged in") != std::string::npos) std::cout << "User already logged in" << std::endl;
        else if (errorMsg.find("Wrong password") != std::string::npos) std::cout << "Wrong password" << std::endl;
        else std::cout << errorMsg << std::endl;

        handler.close();
        connected = false;
    }
}

// YA: Helper to generate SUBSCRIBE frames [cite: 143]
std::string StompProtocol::createSubscribeFrame(std::string topic) {
    int subId = subscriptionIdCounter++;
    int rId = receiptIdCounter++;
    topicToId[topic] = subId;
    idToTopic[subId] = topic;
    receiptToCommand[rId] = "join " + topic;

    std::string frame = "SUBSCRIBE\n";
    frame += "destination:/" + topic + "\n";
    frame += "id:" + std::to_string(subId) + "\n";
    frame += "receipt:" + std::to_string(rId) + "\n\n";
    return frame;
}

// YA: Generate summary file with sorted events and lexicographical stats [cite: 446, 467]
void StompProtocol::generateSummary(std::string gameName, std::string user, std::string fileName) {
    std::ofstream outFile(fileName);
    if (!gameReports.count(gameName) || !gameReports[gameName].count(user)) {
        std::cout << "No reports found for " << user << " in " << gameName << std::endl;
        return;
    }

    auto& events = gameReports[gameName][user];
    // YA: Sort events chronologically [cite: 379, 467]
    std::sort(events.begin(), events.end(), [](const Event& a, const Event& b) {
        return a.get_time() < b.get_time();
    });

    std::string teamA = events[0].get_team_a_name();
    std::string teamB = events[0].get_team_b_name();
    // YA: Use std::map to ensure lexicographical order of keys 
    std::map<std::string, std::string> fGen, fA, fB;
    
    for (const auto& e : events) {
        for (auto const& [k, v] : e.get_game_updates()) fGen[k] = v;
        for (auto const& [k, v] : e.get_team_a_updates()) fA[k] = v;
        for (auto const& [k, v] : e.get_team_b_updates()) fB[k] = v;
    }

    outFile << teamA << " vs " << teamB << "\n";
    outFile << "Game stats:\nGeneral stats:\n";
    for (auto const& [k, v] : fGen) outFile << k << ": " << v << "\n";
    outFile << teamA << " stats:\n";
    for (auto const& [k, v] : fA) outFile << k << ": " << v << "\n";
    outFile << teamB << " stats:\n";
    for (auto const& [k, v] : fB) outFile << k << ": " << v << "\n";

    outFile << "\nGame event reports:\n";
    for (const auto& e : events) {
        outFile << e.get_time() << " - " << e.get_name() << ":\n\n";
        outFile << e.get_discription() << "\n\n";
    }
    outFile.close();
    std::cout << "Summary generated in " << fileName << std::endl;
}

std::string StompProtocol::getLatestConnectFrame() {
    return pendingConnectFrame;
}

bool StompProtocol::isConnected() {
    return connected;
}