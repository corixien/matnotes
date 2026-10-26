const int = (v, d) => (v === undefined || v === '' ? d : Number.parseInt(v, 10));

const config = {
    port: int(process.env.PORT, 3000),
    databaseUrl: process.env.DATABASE_URL,
    sslRejectUnauthorized: process.env.DATABASE_SSL_REJECT_UNAUTHORIZED !== 'false',
    jwtSecret: process.env.JWT_SECRET,
    accessTokenSeconds: int(process.env.ACCESS_TOKEN_SECONDS, 15 * 60),
    refreshTokenDays: int(process.env.REFRESH_TOKEN_DAYS, 90),
    maxFileBytes: int(process.env.MAX_FILE_BYTES, 25 * 1024 * 1024),
    userQuotaBytes: int(process.env.USER_QUOTA_BYTES, 200 * 1024 * 1024),
    keepVersions: int(process.env.KEEP_VERSIONS, 2),
    kdfIterations: 600000,
};

function assertConfig() {
    if (!config.databaseUrl) throw new Error('DATABASE_URL is required');
    if (!config.jwtSecret || config.jwtSecret.length < 32) throw new Error('JWT_SECRET (min. 32 chars) is required');
}

module.exports = { config, assertConfig };
