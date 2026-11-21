export interface ApiResponse<T> {
  status: number;
  data: T;
  message: string;
  timestamp: string;
}

export interface CursorPage<T> {
  content: T[];
  nextCursor: string | null;
  size: number;
  totalElements: number;
}

export interface ValidationErrors {
  [field: string]: string;
}
