import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 300,           // 并发用户数（虚拟用户）
    duration: '30s',    // 持续 30 秒
};

export default function () {
    const res = http.get('http://localhost:8080/mock/ai');
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
}