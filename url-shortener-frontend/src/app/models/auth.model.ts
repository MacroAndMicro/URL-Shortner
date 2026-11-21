export interface UserProfile {
  id: number;
  username: string;
  email: string;
  roles: string[];
  createdDate: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  refreshToken: string;
}

export interface RefreshRequest {
  refreshToken: string;
}
