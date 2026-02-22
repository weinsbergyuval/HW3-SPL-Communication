# STOMP Client-Server Project

## ⚡ Quick Start

### 1. Build (First Time Only)

**Server:**
```bash
cd /workspaces/Assignment\ 3\ SPL/server && mvn compile
```

**Client:**
```bash
cd /workspaces/Assignment\ 3\ SPL/client && make
```

### 2. Run (3 Terminals)

**Terminal 1 - SQL Server:**
```bash
cd /workspaces/Assignment\ 3\ SPL/data && python3 sql_server.py 7778
```

**Terminal 2 - STOMP Server:**
```bash
cd /workspaces/Assignment\ 3\ SPL/server && mvn exec:java -Dexec.mainClass="bgu.spl.net.impl.stomp.StompServer" -Dexec.args="7777 tpc"
```

**Terminal 3 - Client:**
```bash
cd /workspaces/Assignment\ 3\ SPL/client && ./bin/StompWCIClient
```

---

## 📖 Client Commands

```text
login 127.0.0.1:7777 user1 pass1
join Germany_Japan
report data/events1_partial.json
summary Germany_Japan user1 output.txt
logout
```

---

## 🧹 Cleanup
```bash
cd /workspaces/Assignment\ 3\ SPL/server && mvn clean
cd /workspaces/Assignment\ 3\ SPL/client && make clean
rm -f /workspaces/Assignment\ 3\ SPL/data/stomp_server.db
```