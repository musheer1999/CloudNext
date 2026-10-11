import { useState, type FormEvent } from 'react'
import { fetchTicket, submitRun } from './api'

export function SubmitForm({ onSubmitted }: { onSubmitted: (id: number) => void }) {
  const [gerritUrl, setGerritUrl] = useState('')
  const [ticketText, setTicketText] = useState('')
  const [jiraUrl, setJiraUrl] = useState('')
  const [wikiUrls, setWikiUrls] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fetching, setFetching] = useState(false)

  async function fetchJira() {
    setFetching(true)
    setError(null)
    try {
      const { text } = await fetchTicket(jiraUrl.trim())
      setTicketText(text)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setFetching(false)
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const id = await submitRun(gerritUrl.trim(), jiraUrl.trim(), wikiUrls.split('\n'), ticketText)
      setGerritUrl('')
      setTicketText('')
      setJiraUrl('')
      setWikiUrls('')
      onSubmitted(id)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <label>
        Gerrit change link <span className="required">*</span>
        <input
          type="url"
          required
          placeholder="https://gerrit.example.com/c/project/+/12345"
          value={gerritUrl}
          onChange={(e) => setGerritUrl(e.target.value)}
        />
      </label>
      <label>
        Ticket text (what the change must do)
        <textarea
          rows={5}
          placeholder="Paste the JIRA ticket: summary, description, acceptance criteria"
          value={ticketText}
          onChange={(e) => setTicketText(e.target.value)}
        />
      </label>
      <label>
        JIRA ticket link
        <span className="input-row">
          <input
            type="url"
            placeholder="https://jira.example.com/browse/ABC-123"
            value={jiraUrl}
            onChange={(e) => setJiraUrl(e.target.value)}
          />
          <button type="button" className="ghost small" disabled={!jiraUrl.trim() || fetching} onClick={fetchJira}>
            {fetching ? '…' : 'Fetch'}
          </button>
        </span>
      </label>
      <label>
        Wiki links (one per line)
        <textarea rows={2} value={wikiUrls} onChange={(e) => setWikiUrls(e.target.value)} />
      </label>
      {gerritUrl.trim() && !ticketText.trim() && !jiraUrl.trim() && (
        <p className="warning">
          No ticket text: the AI can only guess the rules from the code. Paste the ticket for real results.
        </p>
      )}
      <p className="hint">The AI reads the ticket text, the JIRA issue and the wiki pages (fetched when the run starts), and the code change.</p>
      {error && <p className="error">{error}</p>}
      <button type="submit" className="primary" disabled={busy}>
        {busy ? 'Submitting…' : 'Start run'}
      </button>
    </form>
  )
}
