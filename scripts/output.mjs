export function bold(value, stream = process.stdout) {
  const enabled =
    !('NO_COLOR' in process.env) &&
    (stream.isTTY ||
      (process.env.FORCE_COLOR && process.env.FORCE_COLOR !== '0'));
  return enabled ? `\u001b[1m${value}\u001b[22m` : String(value);
}

export function descriptionLines(description, indent = '    ') {
  const columns =
    process.stdout.columns || Number.parseInt(process.env.COLUMNS, 10) || 100;
  const width = Math.max(20, columns - indent.length);
  const lines = [];
  let line = '';
  for (const word of description.trim().split(/\s+/)) {
    if (line && line.length + word.length + 1 > width) {
      lines.push(indent + line);
      line = '';
    }
    line = line ? `${line} ${word}` : word;
  }
  if (line) lines.push(indent + line);
  return lines.join('\n');
}
