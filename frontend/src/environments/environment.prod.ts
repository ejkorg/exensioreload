export const environment = {
    production: true,
    apiBaseUrl: '',
    useProxy: false,
    apiUrl: '/resender/api',
    showSenderLookupSql: false,
    showPreviewDebug: false,
    monitoring: {
        sseConnectTimeoutMs: 4000,
        pollingIntervalMs: 5000,
        initialLoadDelayMs: 100,
        pollingStartDelayMs: 1000,
        sseReconnectIntervalMs: 20000,
        monitorPageSize: 1000,
        monitorMaxRows: 20000
    }
};
