import { act } from '@testing-library/react';

/** Controllable browser transport for the hooks that open a socket and the screens that use them. */
export class FakeSocket {
  static sockets: FakeSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event: { code: number; reason: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(public url: string) {
    FakeSocket.sockets.push(this);
  }

  close() { this.closed = true; }

  static get latest(): FakeSocket {
    const socket = FakeSocket.sockets[FakeSocket.sockets.length - 1];
    if (!socket) throw new Error('no socket opened');
    return socket;
  }

  /** The newest socket finishes its handshake. */
  static open() {
    act(() => FakeSocket.latest.onopen?.());
  }

  static push(value: unknown) {
    FakeSocket.raw(JSON.stringify(value));
  }

  static raw(data: string) {
    act(() => FakeSocket.latest.onmessage?.({ data }));
  }
}
