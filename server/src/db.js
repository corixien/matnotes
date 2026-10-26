const { Pool } = require('pg');
const { config } = require('./config');

let pool;

function getPool() {
    if (!pool) {
        const local = /@(localhost|127\.0\.0\.1)[:/]/.test(config.databaseUrl || '');
        pool = new Pool({
            connectionString: config.databaseUrl,
            max: Number.parseInt(process.env.DATABASE_POOL_MAX || '5', 10),
            ssl: local ? false : { rejectUnauthorized: config.sslRejectUnauthorized },
        });
    }
    return pool;
}

const query = (text, params) => getPool().query(text, params);

async function transaction(fn) {
    const client = await getPool().connect();
    try {
        await client.query('BEGIN');
        const result = await fn(client);
        await client.query('COMMIT');
        return result;
    } catch (err) {
        await client.query('ROLLBACK').catch(() => {});
        throw err;
    } finally {
        client.release();
    }
}

// Writers that bump change_seq run one at a time: sequence order then equals commit order, so a client's cursor
// ("everything up to change_seq N") can never skip a row that commits late.
const WRITE_LOCK = 7001;
const writeTransaction = (fn) => transaction(async (c) => {
    await c.query('SELECT pg_advisory_xact_lock($1)', [WRITE_LOCK]);
    return fn(c);
});

async function close() {
    if (pool) await pool.end();
    pool = undefined;
}

module.exports = { query, transaction, writeTransaction, close };
