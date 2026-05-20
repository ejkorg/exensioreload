export const environment = {
    production: false,
    apiBaseUrl: 'http://127.0.0.1:8080',
    useProxy: true,
    apiUrl: '/api',
    showSenderLookupSql: true,
    showPreviewDebug: true,
    monitoring: {
        sseConnectTimeoutMs: 3000,
        pollingIntervalMs: 5000,
        initialLoadDelayMs: 100,
        pollingStartDelayMs: 1000,
        sseReconnectIntervalMs: 15000,
        monitorPageSize: 1000,
        monitorMaxRows: 20000
    }
};
