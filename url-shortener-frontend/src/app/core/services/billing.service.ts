import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../environments/environment';
import { ApiResponse } from '../../models/api-response.model';
import { PlanName } from '../../models/plan.model';

/** GET /api/v1/billing/prices */
export interface Price {
  plan: PlanName;
  amountPaise: number;
  currency: string;
  period: 'month' | 'year';
}

export interface Prices {
  /** False until the backend has Razorpay keys. */
  paymentsEnabled: boolean;
  plans: Price[];
}

/** What Razorpay Checkout needs to take a payment for an order. */
export interface CheckoutOrder {
  orderId: string;
  keyId: string;
  amountPaise: number;
  currency: string;
  plan: PlanName;
  description: string;
  email: string | null;
  username: string;
}

export interface Payment {
  id: number;
  plan: PlanName;
  amountPaise: number;
  currency: string;
  status: 'CREATED' | 'PAID' | 'FAILED';
  createdDate: string;
  paidDate: string | null;
  /** The plan's new end date once this payment was applied. */
  periodEndAfter: string | null;
}

/** What Razorpay Checkout hands back after a successful payment. */
export interface CheckoutResult {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

/** e.g. "₹99" for 9900 paise. */
export function formatPrice(amountPaise: number, currency = 'INR'): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency', currency, minimumFractionDigits: 0, maximumFractionDigits: 2,
  }).format(amountPaise / 100);
}

/** Paying for Monthly and Yearly through Razorpay. */
@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/v1/billing`;
  private checkoutScript?: Promise<void>;

  prices(): Observable<Prices> {
    return this.http.get<ApiResponse<Prices>>(`${this.baseUrl}/prices`).pipe(map((r) => r.data));
  }

  createOrder(plan: PlanName): Observable<CheckoutOrder> {
    return this.http.post<ApiResponse<CheckoutOrder>>(`${this.baseUrl}/orders`, { plan }).pipe(map((r) => r.data));
  }

  verify(result: CheckoutResult): Observable<ApiResponse<Payment>> {
    return this.http.post<ApiResponse<Payment>>(`${this.baseUrl}/verify`, result);
  }

  payments(): Observable<Payment[]> {
    return this.http.get<ApiResponse<Payment[]>>(`${this.baseUrl}/payments`).pipe(map((r) => r.data));
  }

  /**
   * Opens Razorpay Checkout for an order. Resolves with the signed result when
   * the user pays, with null when they close the window, and rejects when
   * Razorpay reports a failed payment or the script cannot load.
   */
  async openCheckout(order: CheckoutOrder): Promise<CheckoutResult | null> {
    await this.loadCheckoutScript();
    return new Promise((resolve, reject) => {
      const rzp = new (window as any).Razorpay({
        key: order.keyId,
        order_id: order.orderId,
        amount: order.amountPaise,
        currency: order.currency,
        name: 'ShortLink',
        description: order.description,
        prefill: { email: order.email ?? undefined, name: order.username },
        theme: { color: '#ee6123' },
        handler: (result: CheckoutResult) => resolve(result),
        modal: { ondismiss: () => resolve(null) },
      });
      rzp.on('payment.failed', (response: any) =>
        reject(new Error(response?.error?.description ?? 'The payment failed.')));
      rzp.open();
    });
  }

  /** Loads checkout.js once, the first time someone pays. */
  private loadCheckoutScript(): Promise<void> {
    if (!this.checkoutScript) {
      this.checkoutScript = new Promise<void>((resolve, reject) => {
        const script = document.createElement('script');
        script.src = 'https://checkout.razorpay.com/v1/checkout.js';
        script.onload = () => resolve();
        script.onerror = () => {
          this.checkoutScript = undefined;
          reject(new Error('Could not load the payment window. Check your connection and try again.'));
        };
        document.body.appendChild(script);
      });
    }
    return this.checkoutScript;
  }
}
