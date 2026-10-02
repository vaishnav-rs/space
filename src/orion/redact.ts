const PATTERNS: [RegExp, string][] = [
  [
    /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g,
    "[redacted private key]",
  ],
  [/\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g, "[redacted github token]"],
  [/\bsk-[A-Za-z0-9_-]{20,}\b/g, "[redacted api key]"],
  [/\b(authorization|bearer)\b[:\s]+[A-Za-z0-9._~+/=-]{12,}/gi, "$1: [redacted]"],
  [
    /\b([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|PASSWD|API_?KEY|PRIVATE_?KEY)[A-Z0-9_]*)\s*[=:]\s*\S+/gi,
    "$1=[redacted]",
  ],
  [
    /\b(postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis):\/\/[^\s:@/]+:[^\s@/]+@/gi,
    "$1://[redacted]@",
  ],
];

/** Removes credentials from anything that is stored on a task or shown to a model. */
export function redactSecrets(text: string): string {
  return PATTERNS.reduce((acc, [re, replacement]) => acc.replace(re, replacement), text);
}

export function tail(text: string, maxChars = 4000): string {
  return text.length <= maxChars ? text : `…${text.slice(-maxChars)}`;
}
