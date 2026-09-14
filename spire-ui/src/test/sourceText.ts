/** Keep quoted strings intact: the /runs/* route is not the start of a block comment. */
export function withoutBlockComments(text: string): string {
  return text.replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|\/\*[\s\S]*?\*\//g,
    token => token.startsWith('/*') ? '' : token);
}
