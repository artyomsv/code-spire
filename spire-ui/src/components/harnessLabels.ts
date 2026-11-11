/** How a harness's config key reads on screen; a key with no entry reads as itself. */
export const HARNESS_LABEL: Record<string, string> = { codex: 'Codex', claude_code: 'Claude Code' };

export function harnessLabel(key: string): string {
  return HARNESS_LABEL[key] ?? key;
}
