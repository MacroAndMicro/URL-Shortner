import { Injectable } from '@angular/core';

const TOKEN_KEY = 'access_token';
const REFRESH_TOKEN_KEY = 'refresh_token';
const GUEST_ID_KEY = 'guest_id';

/**
 * Browser storage for tokens and the guest ID. localStorage can be missing or
 * throw (blocked site data, some private modes); then values live in memory
 * for this tab only, instead of the app failing to start.
 */
@Injectable({ providedIn: 'root' })
export class StorageService {
  private readonly memory = new Map<string, string>();

  private read(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return this.memory.get(key) ?? null;
    }
  }

  private write(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      this.memory.set(key, value);
    }
  }

  private remove(key: string): void {
    try {
      localStorage.removeItem(key);
    } catch {
      // Not stored there.
    }
    this.memory.delete(key);
  }

  getToken(): string | null {
    return this.read(TOKEN_KEY);
  }

  setToken(token: string): void {
    this.write(TOKEN_KEY, token);
  }

  getRefreshToken(): string | null {
    return this.read(REFRESH_TOKEN_KEY);
  }

  setRefreshToken(token: string): void {
    this.write(REFRESH_TOKEN_KEY, token);
  }

  clear(): void {
    this.remove(TOKEN_KEY);
    this.remove(REFRESH_TOKEN_KEY);
  }

  isAuthenticated(): boolean {
    return !!this.getToken();
  }

  /**
   * A random ID for this browser, which the backend uses to allow guests one
   * active link. Kept across logins and logouts (clear() leaves it alone).
   */
  getGuestId(): string {
    let id = this.read(GUEST_ID_KEY);
    if (!id) {
      id = crypto.randomUUID();
      this.write(GUEST_ID_KEY, id);
    }
    return id;
  }
}
