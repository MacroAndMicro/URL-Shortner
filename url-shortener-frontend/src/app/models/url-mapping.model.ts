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
}

export interface UpdateUrlRequest {
  originalUrl: string;
}
