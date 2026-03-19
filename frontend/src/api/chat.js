const API = '/api'

/* ── Agents ── */
export const fetchAgents = () => fetch(`${API}/agents`).then(r => r.json())

/* ── Models ── */
export const fetchModels = () => fetch(`${API}/models`).then(r => r.json())

/* ── Workspace ── */
export const connectWorkspace = (path) =>
  fetch(`${API}/workspace/connect`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path })
  }).then(r => r.json())

export const getWorkspaceStatus = () => fetch(`${API}/workspace/status`).then(r => r.json())
export const getFileTree = () => fetch(`${API}/workspace/tree`).then(r => r.json())
export const readFile = (path) => fetch(`${API}/workspace/file?path=${encodeURIComponent(path)}`).then(r => r.json())

/* ── Terminal ── */
export const getPendingCommands = () => fetch(`${API}/terminal/pending`).then(r => r.json())
export const approveCommand = (id) => fetch(`${API}/terminal/approve/${id}`, { method: 'POST' }).then(r => r.json())
export const rejectCommand = (id) => fetch(`${API}/terminal/reject/${id}`, { method: 'POST' }).then(r => r.json())

/* ── Chat (SSE streaming) ── */
export async function streamChat(input, conversationId, { onToken, onDone, onError, model }) {
  try {
    const body = { input, conversationId }
    if (model) body.model = model

    const res = await fetch(`${API}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })

    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: 'Request failed' }))
      onError(err.error || `HTTP ${res.status}`)
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop()

      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const jsonStr = line.slice(5).trim()
        if (!jsonStr) continue
        try {
          const data = JSON.parse(jsonStr)
          if (data.error) { onError(data.error); return }
          if (data.done) onDone(data.agentType)
          else onToken(data.token, data.agentType)
        } catch {}
      }
    }
  } catch (err) {
    onError(err.message || 'Network error')
  }
}
