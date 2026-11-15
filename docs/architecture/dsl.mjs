// Short forms for the sheet files.
export const n = (id, kind, x, y, w, h, title, lines = [], extra = {}) => ({ id, kind, x, y, w, h, title, lines, ...extra });
export const e = (a, b, kind, label, extra = {}) => ({ a, b, kind, label, ...extra });
export const g = (id, x, y, w, h, label, extra = {}) => ({ id, x, y, w, h, label, ...extra });
