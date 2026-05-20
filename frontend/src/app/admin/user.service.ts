import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, BehaviorSubject, tap } from 'rxjs';
import { environment } from '../../environments/environment';

export interface User {
    id: number;
    username: string;
    email: string;
    enabled: boolean;
    status: 'ACTIVE' | 'INACTIVE' | 'LOCKED';
    roles: string[];
    createdAt: string;
    updatedAt: string;
    lastLoginAt?: string;
}

export interface UserPage {
    content: User[];
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
}

export interface UserStatistics {
    totalUsers: number;
    activeUsers: number;
    inactiveUsers: number;
    lockedUsers: number;
    superAdmins: number;
    admins: number;
}

@Injectable({
    providedIn: 'root'
})
export class UserService {
    private readonly http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/admin/users`;

    private usersSubject = new BehaviorSubject<User[]>([]);
    users$ = this.usersSubject.asObservable();

    getUsers(params: {
        page?: number;
        size?: number;
        search?: string;
        role?: string;
        status?: string;
        sortBy?: string;
        sortDir?: string;
    } = {}): Observable<UserPage> {
        let httpParams = new HttpParams();
        if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
        if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());
        if (params.search) httpParams = httpParams.set('search', params.search);
        if (params.role) httpParams = httpParams.set('role', params.role);
        if (params.status) httpParams = httpParams.set('status', params.status);
        if (params.sortBy) httpParams = httpParams.set('sortBy', params.sortBy);
        if (params.sortDir) httpParams = httpParams.set('sortDir', params.sortDir);

        return this.http.get<UserPage>(this.apiUrl, { params: httpParams, withCredentials: true }).pipe(
            tap(res => this.usersSubject.next(res.content))
        );
    }

    getUserById(id: number): Observable<User> {
        return this.http.get<User>(`${this.apiUrl}/${id}`, { withCredentials: true });
    }

    createUser(user: any): Observable<User> {
        return this.http.post<User>(this.apiUrl, user, { withCredentials: true });
    }

    updateUser(id: number, user: any): Observable<User> {
        return this.http.put<User>(`${this.apiUrl}/${id}`, user, { withCredentials: true });
    }

    deleteUser(id: number): Observable<void> {
        return this.http.delete<void>(`${this.apiUrl}/${id}`, { withCredentials: true });
    }

    getStatistics(): Observable<UserStatistics> {
        return this.http.get<UserStatistics>(`${this.apiUrl}/statistics`, { withCredentials: true });
    }

    getAvailableRoles(): Observable<string[]> {
        return this.http.get<string[]>(`${this.apiUrl}/roles`, { withCredentials: true });
    }
}
