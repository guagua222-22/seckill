/**
 * M6 压测脚本（Node 18+，无第三方依赖）。
 *
 * 为什么不用 JMeter：本轮要验证的是 Sentinel 限流命中与多实例负载均衡，
 * 需要按"HTTP 状态码 + 业务 code"双维度分类统计（网关限流返回 HTTP 429，
 * 服务内限流返回 HTTP 200 + body.code=429），并且每单要带唯一 requestId。
 * 用 fetch 自己写 200 行比调 JMeter 的 XML 更直观、结果也更好读。
 *
 * 用法：
 *   node scripts/loadtest/load.mjs --mode order  --base http://localhost:8080 \
 *        --activity 2098646025382559745 --users 2096435620350492674,2097946090156847107 \
 *        --total 20000 --concurrency 200
 *   node scripts/loadtest/load.mjs --mode detail --base http://localhost:8080 \
 *        --goods 2098645910462824450 --total 20000 --concurrency 200
 *   node scripts/loadtest/load.mjs --mode stock  --base http://localhost:8080 --activity <id> --total 5000
 *
 * 参数：
 *   --mode         order=下单接口 / detail=商品详情（热点隔离对比）/ stock=Redis 实时库存
 *   --base         入口地址（默认走网关 8080，也可直连某实例端口验证负载均衡）
 *   --total        总请求数
 *   --concurrency  并发协程数（不是线程，Node 单线程事件循环靠 fetch 并发）
 *   --activity     活动 ID（order/stock 必填）
 *   --goods        商品 ID（detail 必填）
 *   --users        逗号分隔的用户 ID 列表，轮转使用（order 必填，用户必须在 user 库真实存在）
 *   --timeout      单请求超时毫秒，默认 5000
 */

const args = parseArgs(process.argv.slice(2));
const MODE = args.mode ?? 'order';
const BASE = (args.base ?? 'http://localhost:8080').replace(/\/$/, '');
const TOTAL = Number(args.total ?? 5000);
const CONCURRENCY = Number(args.concurrency ?? 100);
const TIMEOUT = Number(args.timeout ?? 5000);
const RUN_ID = Date.now().toString(36);

const endpoint = buildEndpoint();
console.log(`压测开始: mode=${MODE} url=${endpoint.url} total=${TOTAL} concurrency=${CONCURRENCY}`);

/** 分类计数器：key = "HTTP状态/业务码"，一眼看出限流命中在网关层还是服务层 */
const counters = new Map();
const latencies = [];
let issued = 0;
let transportErrors = 0;

const startedAt = performance.now();
await runPool();
const elapsedMs = performance.now() - startedAt;

report();

function buildEndpoint() {
    switch (MODE) {
        case 'order': {
            requireArg('activity');
            requireArg('users');
            const userIds = String(args.users).split(',').map(s => s.trim()).filter(Boolean);
            return {
                url: `${BASE}/api/seckill/order`,
                init: (seq) => ({
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    // requestId 必须每单唯一：重复会被幂等快路径拦掉，压测就变成在测幂等而不是测限流
                    body: JSON.stringify({
                        userId: userIds[seq % userIds.length],
                        activityId: args.activity,
                        requestId: `load-${RUN_ID}-${seq}`,
                    }),
                }),
            };
        }
        case 'detail': {
            requireArg('goods');
            return { url: `${BASE}/api/goods/${args.goods}`, init: () => ({ method: 'GET' }) };
        }
        case 'stock': {
            requireArg('activity');
            return { url: `${BASE}/api/seckill/stock/${args.activity}`, init: () => ({ method: 'GET' }) };
        }
        default:
            throw new Error(`未知 mode: ${MODE}（可选 order/detail/stock）`);
    }
}

async function runPool() {
    const workers = Array.from({ length: Math.min(CONCURRENCY, TOTAL) }, async () => {
        while (true) {
            const seq = issued++;
            if (seq >= TOTAL) {
                return;
            }
            await oneRequest(seq);
        }
    });
    await Promise.all(workers);
}

async function oneRequest(seq) {
    const begin = performance.now();
    try {
        const res = await fetch(endpoint.url, {
            ...endpoint.init(seq),
            signal: AbortSignal.timeout(TIMEOUT),
        });
        const text = await res.text();
        latencies.push(performance.now() - begin);
        let bizCode = '-';
        try {
            bizCode = JSON.parse(text).code ?? '-';
        } catch {
            bizCode = 'non-json';
        }
        bump(`${res.status}/${bizCode}`);
    } catch (err) {
        // 连接被拒/超时/网关断连：与业务响应分开计，避免把基础设施问题算成限流
        transportErrors++;
        bump(`transport-error/${err.name ?? 'Error'}`);
    }
}

function bump(key) {
    counters.set(key, (counters.get(key) ?? 0) + 1);
}

function report() {
    const seconds = elapsedMs / 1000;
    const ok = [...counters.entries()]
        .filter(([k]) => k.startsWith('200/0'))
        .reduce((sum, [, v]) => sum + v, 0);
    const limited = [...counters.entries()]
        .filter(([k]) => k.includes('/429') || k.startsWith('429/'))
        .reduce((sum, [, v]) => sum + v, 0);

    console.log('\n===== 结果 =====');
    console.log(`耗时        : ${seconds.toFixed(2)}s`);
    console.log(`总请求      : ${TOTAL}（成功发出 ${TOTAL - transportErrors}，传输失败 ${transportErrors}）`);
    console.log(`吞吐        : ${(TOTAL / seconds).toFixed(0)} req/s`);
    console.log(`业务成功    : ${ok}（code=0，秒杀场景含"排队中"）`);
    console.log(`被限流      : ${limited}（HTTP 429 = 网关层拦；200/429 = 服务内 Sentinel 拦）`);
    console.log(`延迟 ms     : ${percentile(50)} (p50) / ${percentile(95)} (p95) / ${percentile(99)} (p99) / ${percentile(100)} (max)`);
    console.log('\n分类明细:');
    for (const [key, count] of [...counters.entries()].sort((a, b) => b[1] - a[1])) {
        console.log(`  ${key.padEnd(28)} ${String(count).padStart(8)}  ${(count / TOTAL * 100).toFixed(1)}%`);
    }
}

function percentile(p) {
    if (latencies.length === 0) {
        return 0;
    }
    const sorted = [...latencies].sort((a, b) => a - b);
    const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
    return Math.round(sorted[Math.max(0, idx)]);
}

function parseArgs(argv) {
    const out = {};
    for (let i = 0; i < argv.length; i++) {
        if (!argv[i].startsWith('--')) {
            continue;
        }
        const key = argv[i].slice(2);
        const next = argv[i + 1];
        if (next === undefined || next.startsWith('--')) {
            out[key] = true;
        } else {
            out[key] = next;
            i++;
        }
    }
    return out;
}

function requireArg(name) {
    if (args[name] === undefined) {
        throw new Error(`mode=${MODE} 需要 --${name} 参数`);
    }
}
