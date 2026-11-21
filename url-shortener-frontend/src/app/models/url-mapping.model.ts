export interface UrlMapping {
  id: string;
  originalUrl: string;
  shortUrl: string;
  clickCount: number;
  createdDate: string;
  username: string;
  /** When the link stops working; null if it never expires. */
  expiresAt: string | null;
}

export interface CreateUrlRequest {
  originalUrl: string;
  /** Yearly plan only; 4 to 30 letters, digits or hyphens. */
  customCode?: string;
}

export interface UpdateUrlRequest {
  originalUrl: string;
}
