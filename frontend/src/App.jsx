import React, { useState, useRef, useEffect, useCallback } from 'react'
import {
  fetchAgents, fetchModels, connectWorkspace, getWorkspaceStatus,
  getFileTree, readFile, getPendingCommands,
  approveCommand, rejectCommand, streamChat
} from './api/chat'

const COLORS = { CODE: '#22c55e', FINANCE: '#eab308', RESEARCH: '#3b82f6', GENERAL: '#8b5cf6' }
const getColor = t => COLORS[t] || '#888'

/* ── Styles ── */
const s = {
  panel: { background: '#0d0d1a', borderRight: '1px solid #1a1a2e', overflowY: 'auto', flexShrink: 0 },
  label: { fontSize: '10px', color: '#555', textTransform: 'uppercase', letterSpacing: '1.5px', fontWeight: 700, marginBottom: '10px' },
  input: { width: '100%', background: '#12121f', border: '1px solid #2a2a3d', borderRadius: '8px', padding: '8px 10px', color: '#e2e8f0', fontSize: '13px', outline: 'none', boxSizing: 'border-box' },
  btn: (bg) => ({ background: bg, color: '#fff', border: 'none', borderRadius: '8px', padding: '6px 14px', fontSize: '12px', cursor: 'pointer', fontWeight: 600 }),
}

/* ── Agent Badge ── */
function Badge({ type }) {
  if (!type) return null
  const c = getColor(type)
  return <span style={{ background: c + '20', color: c, padding: '2px 8px', borderRadius: '10px', fontSize: '10px', fontWeight: 700, border: `1px solid ${c}30` }}>{type}</span>
}

/* ── File Tree Item ── */
function FileNode({ node, depth = 0, onSelect }) {
  const [open, setOpen] = useState(depth < 1)
  const isDir = node.isDirectory
  return (
    <div>
      <div
        onClick={() => isDir ? setOpen(!open) : onSelect(node.path)}
        style={{ padding: '3px 0', paddingLeft: depth * 14, cursor: 'pointer', fontSize: '12px', color: isDir ? '#8b8ba7' : '#c4c4d4', display: 'flex', alignItems: 'center', gap: '4px' }}
      >
        <span style={{ fontSize: '10px', width: '14px' }}>{isDir ? (open ? '▼' : '▶') : '·'}</span>
        {node.name}
      </div>
      {isDir && open && node.children?.map((c, i) => <FileNode key={i} node={c} depth={depth + 1} onSelect={onSelect} />)}
    </div>
  )
}

/* ── Pending Command Card ── */
function CommandCard({ cmd, onApprove, onReject }) {
  return (
    <div style={{ background: '#1a1520', border: '1px solid #3b2d4d', borderRadius: '8px', padding: '10px', marginBottom: '8px' }}>
      <div style={{ fontFamily: 'monospace', fontSize: '12px', color: '#f59e0b', marginBottom: '4px', wordBreak: 'break-all' }}>{cmd.command}</div>
      <div style={{ fontSize: '11px', color: '#777', marginBottom: '8px' }}>{cmd.reason}</div>
      <div style={{ display: 'flex', gap: '6px' }}>
        <button onClick={() => onApprove(cmd.id)} style={s.btn('#22c55e')}>Approve</button>
        <button onClick={() => onReject(cmd.id)} style={s.btn('#ef4444')}>Reject</button>
      </div>
    </div>
  )
}

/* ── Message Bubble ── */
function Message({ msg }) {
  const isUser = msg.role === 'user'
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: isUser ? 'flex-end' : 'flex-start', marginBottom: '14px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '3px' }}>
        <span style={{ fontSize: '11px', color: '#666', fontWeight: 600 }}>{isUser ? 'You' : 'Agent'}</span>
        {!isUser && <Badge type={msg.agentType} />}
      </div>
      <div style={{
        background: isUser ? '#2563eb' : '#141422',
        color: '#e2e8f0', padding: '12px 16px',
        borderRadius: isUser ? '16px 16px 4px 16px' : '16px 16px 16px 4px',
        maxWidth: '80%', whiteSpace: 'pre-wrap', lineHeight: '1.65', fontSize: '13.5px',
        border: isUser ? 'none' : '1px solid #222238', wordBreak: 'break-word'
      }}>
        {msg.content}
        {msg.streaming && <span style={{ display: 'inline-block', width: '7px', height: '14px', background: getColor(msg.agentType), marginLeft: '2px', borderRadius: '2px', animation: 'blink 1s infinite' }} />}
      </div>
    </div>
  )
}

/* ═══════════════ MAIN APP ═══════════════ */
export default function App() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [conversationId] = useState(() => crypto.randomUUID())

  // Sidebar state
  const [agents, setAgents] = useState([])
  const [models, setModels] = useState([])
  const [loadedModels, setLoadedModels] = useState([])
  const [selectedModel, setSelectedModel] = useState('') // empty = agent default
  const [workspace, setWorkspace] = useState(null)
  const [fileTree, setFileTree] = useState([])
  const [wsPath, setWsPath] = useState('')
  const [pendingCmds, setPendingCmds] = useState([])
  const [selectedFile, setSelectedFile] = useState(null)
  const [fileContent, setFileContent] = useState('')
  const [activeTab, setActiveTab] = useState('files') // files | agents | terminal

  const endRef = useRef(null)
  const textRef = useRef(null)

  // Init
  useEffect(() => {
    fetchAgents().then(setAgents).catch(() => {})
    fetchModels().then(data => {
      setModels(data.available || [])
      setLoadedModels(data.loaded || [])
    }).catch(() => {})
    getWorkspaceStatus().then(ws => { if (ws.connected) { setWorkspace(ws); loadTree() } }).catch(() => {})
  }, [])

  // Poll pending commands + loaded models
  useEffect(() => {
    const iv = setInterval(() => {
      getPendingCommands().then(setPendingCmds).catch(() => {})
      fetchModels().then(data => setLoadedModels(data.loaded || [])).catch(() => {})
    }, 3000)
    return () => clearInterval(iv)
  }, [])

  // Auto-scroll
  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages])

  // Auto-resize
  useEffect(() => {
    if (textRef.current) { textRef.current.style.height = 'auto'; textRef.current.style.height = Math.min(textRef.current.scrollHeight, 150) + 'px' }
  }, [input])

  const loadTree = () => getFileTree().then(setFileTree).catch(() => {})

  const handleConnect = async () => {
    if (!wsPath.trim()) return
    try {
      const res = await connectWorkspace(wsPath.trim())
      if (res.connected) { setWorkspace(res); loadTree() }
      else alert(res.error || 'Failed to connect')
    } catch (e) { alert('Connection failed: ' + e.message) }
  }

  const handleFileSelect = async (path) => {
    setSelectedFile(path)
    try {
      const res = await readFile(path)
      setFileContent(res.content)
    } catch { setFileContent('Failed to read file') }
  }

  const handleApprove = async (id) => {
    await approveCommand(id)
    setPendingCmds(p => p.filter(c => c.id !== id))
  }

  const handleReject = async (id) => {
    await rejectCommand(id)
    setPendingCmds(p => p.filter(c => c.id !== id))
  }

  const handleSend = async () => {
    const trimmed = input.trim()
    if (!trimmed || loading) return
    setMessages(prev => [...prev, { role: 'user', content: trimmed }])
    setInput('')
    setLoading(true)
    setMessages(prev => [...prev, { role: 'assistant', content: '', streaming: true, agentType: null }])

    await streamChat(trimmed, conversationId, {
      model: selectedModel || undefined,
      onToken: (token, agentType) => {
        setMessages(prev => {
          const u = [...prev]; const last = u[u.length - 1]
          if (last?.role === 'assistant') u[u.length - 1] = { ...last, content: last.content + token, agentType: agentType || last.agentType }
          return u
        })
      },
      onDone: (agentType) => {
        setMessages(prev => {
          const u = [...prev]; const last = u[u.length - 1]
          if (last?.role === 'assistant') u[u.length - 1] = { ...last, streaming: false, agentType }
          return u
        })
        setLoading(false)
        loadTree() // refresh tree after agent might have modified files
      },
      onError: (error) => {
        setMessages(prev => {
          const u = [...prev]; const last = u[u.length - 1]
          if (last?.role === 'assistant') u[u.length - 1] = { ...last, content: 'Error: ' + error, streaming: false }
          return u
        })
        setLoading(false)
      }
    })
  }

  return (
    <div style={{ height: '100vh', display: 'flex', background: '#0a0a14', color: '#e2e8f0', fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif' }}>
      <style>{`@keyframes blink{0%,100%{opacity:1}50%{opacity:0}} ::selection{background:#2563eb50}`}</style>

      {/* ── LEFT SIDEBAR ── */}
      <div style={{ ...s.panel, width: '260px', display: 'flex', flexDirection: 'column' }}>
        {/* Workspace Connect */}
        <div style={{ padding: '12px', borderBottom: '1px solid #1a1a2e' }}>
          {workspace ? (
            <div>
              <div style={{ fontSize: '11px', color: '#22c55e', marginBottom: '2px', fontWeight: 600 }}>CONNECTED</div>
              <div style={{ fontSize: '13px', color: '#ddd', wordBreak: 'break-all' }}>{workspace.name}</div>
              <div style={{ fontSize: '10px', color: '#555', marginTop: '2px' }}>{workspace.path}</div>
            </div>
          ) : (
            <div>
              <div style={s.label}>Connect Workspace</div>
              <input
                value={wsPath}
                onChange={e => setWsPath(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleConnect()}
                placeholder="C:\path\to\project"
                style={{ ...s.input, marginBottom: '6px' }}
              />
              <button onClick={handleConnect} style={s.btn('#2563eb')}>Connect</button>
            </div>
          )}
        </div>

        {/* Tabs */}
        <div style={{ display: 'flex', borderBottom: '1px solid #1a1a2e' }}>
          {['files', 'agents', 'terminal'].map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              style={{
                flex: 1, padding: '8px', fontSize: '11px', fontWeight: 600,
                background: 'transparent', border: 'none', cursor: 'pointer',
                color: activeTab === tab ? '#fff' : '#555',
                borderBottom: activeTab === tab ? '2px solid #2563eb' : '2px solid transparent',
                textTransform: 'uppercase', letterSpacing: '0.5px'
              }}
            >
              {tab}
              {tab === 'terminal' && pendingCmds.length > 0 && (
                <span style={{ background: '#ef4444', color: '#fff', borderRadius: '50%', padding: '1px 5px', fontSize: '9px', marginLeft: '4px' }}>{pendingCmds.length}</span>
              )}
            </button>
          ))}
        </div>

        {/* Tab Content */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '10px' }}>
          {activeTab === 'files' && (
            fileTree.length > 0
              ? fileTree.map((n, i) => <FileNode key={i} node={n} onSelect={handleFileSelect} />)
              : <div style={{ color: '#444', fontSize: '12px' }}>{workspace ? 'No files found' : 'Connect a workspace to browse files'}</div>
          )}

          {activeTab === 'agents' && agents.map(a => (
            <div key={a.name} style={{ padding: '8px 10px', marginBottom: '6px', borderRadius: '8px', background: '#12121f', border: `1px solid ${getColor(a.type)}20` }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px' }}>
                <span style={{ width: '7px', height: '7px', borderRadius: '50%', background: getColor(a.type) }} />
                <span style={{ fontSize: '12px', fontWeight: 600, color: '#ddd' }}>{a.name}</span>
              </div>
              <div style={{ fontSize: '10px', color: '#666', lineHeight: '1.6' }}>
                <div>Type: <span style={{ color: getColor(a.type) }}>{a.type}</span></div>
                <div>Model: <span style={{ color: '#c4c4d4' }}>{a.model || 'default'}</span></div>
                <div>Depth: {a.responseLevel?.toLowerCase()} | Temp: {a.temperature}</div>
                {a.chainOfThought && <div style={{ color: '#4ade80' }}>Chain-of-Thought ON</div>}
                {a.selfVerify && <div style={{ color: '#38bdf8' }}>Self-Verify ON</div>}
              </div>
            </div>
          ))}

          {activeTab === 'terminal' && (
            pendingCmds.length > 0
              ? pendingCmds.map(cmd => <CommandCard key={cmd.id} cmd={cmd} onApprove={handleApprove} onReject={handleReject} />)
              : <div style={{ color: '#444', fontSize: '12px' }}>No pending commands. When an agent proposes a terminal command, it will appear here for your approval.</div>
          )}
        </div>
      </div>

      {/* ── MAIN AREA ── */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        {/* Header */}
        <div style={{ padding: '12px 20px', borderBottom: '1px solid #1a1a2e', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span style={{ fontSize: '15px', fontWeight: 700 }}>Multi-Agent System</span>
            <span style={{ fontSize: '11px', color: '#555' }}>{agents.length} agents</span>
            {workspace && <span style={{ fontSize: '11px', color: '#22c55e' }}>· {workspace.name}</span>}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            {/* Model Selector */}
            <div style={{ position: 'relative' }}>
              <select
                value={selectedModel}
                onChange={e => setSelectedModel(e.target.value)}
                style={{
                  background: '#12121f', color: '#c4c4d4', border: '1px solid #2a2a3d',
                  borderRadius: '8px', padding: '6px 28px 6px 10px', fontSize: '11px',
                  cursor: 'pointer', outline: 'none', appearance: 'none',
                  backgroundImage: 'url("data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' width=\'12\' height=\'12\' viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'%23666\' stroke-width=\'2\'%3E%3Cpath d=\'M6 9l6 6 6-6\'/%3E%3C/svg%3E")',
                  backgroundRepeat: 'no-repeat', backgroundPosition: 'right 8px center'
                }}
              >
                <option value="">Auto (agent default)</option>
                {models.map(m => (
                  <option key={m.name} value={m.name}>
                    {m.name} ({m.parameterSize || m.sizeHuman})
                    {loadedModels.some(l => l.name === m.name) ? ' *' : ''}
                  </option>
                ))}
              </select>
            </div>
            {/* Loaded indicator */}
            {loadedModels.length > 0 && (
              <span style={{ fontSize: '10px', color: '#22c55e' }} title={`Loaded: ${loadedModels.map(m => m.name).join(', ')}`}>
                * loaded
              </span>
            )}
            <button onClick={() => window.location.reload()} style={{ ...s.btn('transparent'), border: '1px solid #333', color: '#777' }}>New Chat</button>
          </div>
        </div>

        {/* File Preview (when a file is selected) */}
        {selectedFile && (
          <div style={{ borderBottom: '1px solid #1a1a2e', maxHeight: '200px', overflowY: 'auto' }}>
            <div style={{ padding: '6px 20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: '#0e0e1c' }}>
              <span style={{ fontSize: '12px', color: '#888', fontFamily: 'monospace' }}>{selectedFile}</span>
              <button onClick={() => { setSelectedFile(null); setFileContent('') }} style={{ ...s.btn('transparent'), color: '#666', padding: '2px 8px', fontSize: '11px' }}>Close</button>
            </div>
            <pre style={{ margin: 0, padding: '10px 20px', fontSize: '12px', color: '#b4b4cc', background: '#0a0a16', overflowX: 'auto', fontFamily: 'Consolas, Monaco, monospace' }}>{fileContent}</pre>
          </div>
        )}

        {/* Messages */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '20px' }}>
          {messages.length === 0 && (
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', gap: '12px', color: '#444' }}>
              <div style={{ fontSize: '28px' }}>*</div>
              <div style={{ fontSize: '14px' }}>
                {workspace ? 'Workspace connected. Ask me to work on your project.' : 'Connect a workspace to get started, or just chat.'}
              </div>
            </div>
          )}
          {messages.map((msg, i) => <Message key={i} msg={msg} />)}
          <div ref={endRef} />
        </div>

        {/* Input */}
        <div style={{ padding: '12px 20px', borderTop: '1px solid #1a1a2e', display: 'flex', gap: '10px', alignItems: 'flex-end' }}>
          <textarea
            ref={textRef}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend() } }}
            placeholder={workspace ? "Ask me to work on your project... (Enter to send)" : "Type your message... (Enter to send)"}
            rows={1}
            style={{ flex: 1, ...s.input, borderRadius: '12px', padding: '12px 16px', fontSize: '14px', resize: 'none', maxHeight: '150px', lineHeight: '1.5' }}
          />
          <button
            onClick={handleSend}
            disabled={loading || !input.trim()}
            style={{ ...s.btn(loading ? '#222' : '#2563eb'), padding: '12px 20px', fontSize: '14px', opacity: loading || !input.trim() ? 0.4 : 1, transition: 'all 0.15s' }}
          >
            {loading ? 'Working...' : 'Send'}
          </button>
        </div>
      </div>
    </div>
  )
}
