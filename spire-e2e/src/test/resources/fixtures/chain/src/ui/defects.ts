export interface Row {
  id: string;
}

export function unusedBinding(rows: Row[]): number {
  const unused = rows.length;  // E2E-DEFECT-C
  return rows.length;
}
