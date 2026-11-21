export interface UrlMapping {
  id: string;
  originalUrl: string;
  shortUrl: string;
  clickCount: number;
  createdDate: string;
  username: string;
}

export interface CreateUrlRequest {
  originalUrl: string;
}

export interface UpdateUrlRequest {
  originalUrl: string;
}
