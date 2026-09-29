const http = require('http');

const PORT = process.env.PORT || 9000;   // ★ 支持环境变量，failover 要起第二个实例

// ★ 故障开关：控制 /mock/fail 是坏是好（验证熔断恢复用）
let failMode = true;

http.createServer((req, res) => {
    // 拆路径和 query：/mock/flaky?failRate=0.5
    const [path, query] = req.url.split('?');
    const params = new URLSearchParams(query || '');

    const send = (status, body) => {
        res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify(body));
    };

    // ① 正常：2 秒延迟（压测脚本不受影响）
    if (path === '/' || path === '/mock/ai') {
        setTimeout(() => send(200, { ok: true, slept: 2 }), 2000);
        return;
    }

    // ② 固定故障：受 failMode 控制（★ 改这里）
    if (path === '/mock/fail') {
        if (failMode) {
            send(500, { error: '模拟上游故障' });
        } else {
            send(200, { ok: true, msg: '已恢复' });   // 同一个 URL 变正常
        }
        return;
    }

    // ③ 开关：打一次翻转一次（★ 新增）
    if (path === '/mock/toggle') {
        failMode = !failMode;
        send(200, { failMode });
        return;
    }

    // ④ 备用模型端点（★ 新增，failover 用）
    if (path === '/mock/backup') {
        setTimeout(() => send(200, { ok: true, model: 'backup', slept: 1 }), 1000);
        return;
    }

    // ⑤ 慢响应：10 秒（测超时熔断用）
    if (path === '/mock/slow') {
        setTimeout(() => send(200, { msg: '慢响应', slept: 10 }), 10000);
        return;
    }

    // ⑥ 可控故障率：?failRate=0.5 → 一半请求 503
    if (path === '/mock/flaky') {
        const rate = parseFloat(params.get('failRate') || '0');
        setTimeout(() => {
            if (Math.random() < rate) {
                send(503, { error: '随机故障' });
            } else {
                send(200, { msg: 'ok' });
            }
        }, 500);
        return;
    }

    // ===== M6：模拟各家 AI 的不同响应格式 =====

    // ① OpenAI / DeepSeek 兼容格式（content 在 choices[0].message.content）
    if (path === '/openai/chat') {
        setTimeout(() => send(200, {
            id: 'chatcmpl-' + Date.now(),
            object: 'chat.completion',
            created: Math.floor(Date.now() / 1000),
            model: 'deepseek-chat',
            choices: [{
                index: 0,
                message: { role: 'assistant', content: '来自 OpenAI 兼容模型' },
                finish_reason: 'stop'
            }],
            usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 }
        }), 1000);
        return;
    }

    // ② 通义原生格式（content 在 output.text，token 字段名也不同）
    if (path === '/qwen/chat') {
        setTimeout(() => send(200, {
            request_id: 'req-' + Date.now(),
            output: { text: '来自通义原生模型', finish_reason: 'stop' },
            usage: { input_tokens: 10, output_tokens: 5, total_tokens: 15 }
        }), 1000);
        return;
    }

    // ③ 「HTTP 200 但业务失败」：余额不足（OpenAI 兼容错误体）
    if (path === '/openai/insufficient') {
        send(200, {
            error: {
                message: '余额不足',
                type: 'insufficient_quota',
                code: 'insufficient_quota'
            }
        });
        return;
    }

    // 未知路径
    send(404, { error: 'not found', path });
}).listen(PORT, () => console.log(`mock on ${PORT}, failMode=${failMode}`));