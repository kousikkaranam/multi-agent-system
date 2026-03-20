# 🧠 Multi-Agent System

A **local-first multi-agent AI workspace assistant** built with **Spring Boot**, **React**, and **Ollama**, designed to deliver **reliable, context-aware outputs** through intelligent routing, deterministic context gathering, and tool-enabled execution.

---

## 🚀 Overview

Multi-Agent System is an AI platform that moves beyond traditional chatbot design by combining:

* 🧭 **Intent-based routing**
* 🧠 **Specialized agents (CODE, RESEARCH, FINANCE, GENERAL)**
* 📂 **Workspace-aware context gathering**
* 🛠️ **Tool execution pipeline**
* ⚡ **Local LLMs via Ollama (no API dependency)**

Instead of letting the LLM guess, the system **collects relevant data first and then generates responses**, significantly reducing hallucinations.

---

## ✨ Features

### 🧭 Intent-Based Routing

* Classifies user queries and routes them to the most relevant agent
* Improves accuracy and reduces unnecessary reasoning overhead

---

### 🤖 Multi-Agent Architecture

* Dedicated agents:

  * `CODE`
  * `RESEARCH`
  * `FINANCE`
  * `GENERAL`
* Each agent has:

  * Custom prompts
  * Model configuration
  * Domain-specific behavior

---

### 🧩 Deterministic Context Gathering

* Collects context before generation:

  * Project files
  * Code search
  * Web data
* Ensures responses are grounded in **real data**

---

### 🛠️ Tool Execution System

Agents can use:

* File read/write
* Codebase search
* Web search & fetch
* Terminal commands

✔ Robust tool parsing
✔ Parallel execution support

---

### 💻 Workspace Awareness

* Connects to local project directories
* Builds file tree
* Enables:

  * Safe file access
  * Context-aware reasoning

---

### ⚠️ Safe Terminal Execution

* Commands require **user approval**
* Prevents unsafe operations

---

### 🌐 Full Stack Interface

**Frontend (React + Vite):**

* Chat UI
* Model selection
* Workspace explorer
* File viewer
* Command approval panel

**Backend (Spring Boot):**

* Agent orchestration
* Context pipeline
* Tool execution engine
* Streaming responses

---

## 🏗️ System Architecture Design

### 🔹 High-Level Architecture

```
                ┌───────────────────────────┐
                │        Frontend UI        │
                │   (React + Vite Client)  │
                └─────────────┬─────────────┘
                              │
                              ▼
                ┌───────────────────────────┐
                │     API Gateway Layer     │
                │   (Spring Boot REST API) │
                └─────────────┬─────────────┘
                              │
                              ▼
                ┌───────────────────────────┐
                │    Agent Orchestrator     │
                │ (Core Control Pipeline)   │
                └─────────────┬─────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌──────────────┐    ┌────────────────┐    ┌────────────────┐
│ Intent Router│    │ Context Engine │    │ Tool Executor  │
└──────┬───────┘    └──────┬─────────┘    └──────┬─────────┘
       │                   │                     │
       ▼                   ▼                     ▼
┌──────────────┐   ┌──────────────────┐   ┌──────────────────┐
│ Agent Manager│   │ Workspace Service│   │ External Tools    │
└──────┬───────┘   └──────────────────┘   └──────────────────┘
       │
       ▼
┌───────────────────────────┐
│     LLM Layer (Ollama)    │
└───────────────────────────┘
```

---

### 🔹 Request Lifecycle (Step-by-Step)

```
1. User sends query
2. Intent Router classifies request
3. Agent is selected (CODE / RESEARCH / etc.)
4. Context Engine gathers:
     - Files
     - Code snippets
     - Web data (if needed)
5. Tool Executor runs required tools
6. Prompt is enhanced with context
7. LLM (Ollama) generates response
8. Response is streamed back to UI
```

---

### 🔹 Core Components

#### 🧭 Intent Router

* Keyword + logic-based classification
* Maps requests → agent types

---

#### 🧠 Agent Orchestrator

* Central brain of the system
* Controls:

  * Flow execution
  * Agent selection
  * Context injection
  * Tool usage

---

#### 🧩 Context Engine

* Decides what data is needed
* Fetches:

  * Files
  * Code search results
  * External content

---

#### 🛠️ Tool Executor

* Executes tool calls from agents
* Handles:

  * Multiple formats
  * Parallel execution
  * Fault tolerance

---

#### 📂 Workspace Service

* Handles local project interaction
* File tree generation
* Safe file access

---

#### ⚠️ Terminal Service

* Queues commands
* Requires manual approval
* Executes securely

---

#### 🤖 LLM Layer (Ollama)

* Runs local models
* No external API dependency
* Supports multiple model selection

---

## 🧪 Tech Stack

| Layer        | Technology              |
| ------------ | ----------------------- |
| Backend      | Java, Spring Boot       |
| Frontend     | React, Vite             |
| AI Runtime   | Ollama                  |
| AI Framework | LangChain4j, Google ADK |
| Architecture | Multi-Agent System      |

---

## 🎯 Design Principles

* **Local-first** → No API dependency
* **Deterministic over guesswork**
* **Specialization over generalization**
* **Control over black-box behavior**

---

## 🔮 Future Improvements

* Persistent memory layer
* Multi-step planning agents
* Plugin/tool ecosystem
* Distributed execution
* Fine-tuned agent models

---

## ⚙️ Setup (Basic)

```bash
# Backend
./mvnw spring-boot:run

# Frontend
cd frontend
npm install
npm run dev
```

---

## 📡 API Endpoints (Sample)

| Endpoint     | Description                      |
| ------------ | -------------------------------- |
| `/chat`      | Chat with agents                 |
| `/agents`    | List available agents            |
| `/models`    | List available models            |
| `/workspace` | Manage workspace                 |
| `/terminal`  | Execute commands (with approval) |

---

## 💡 Philosophy

> “Don’t let the model guess — give it context, then let it answer.”

---

## 🤝 Contributing

PRs and ideas are welcome!
Feel free to open issues or suggest improvements.

---

## 📄 License

MIT License
