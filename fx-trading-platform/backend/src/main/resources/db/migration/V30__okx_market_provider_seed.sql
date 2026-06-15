INSERT INTO market.data_providers (code, name, provider_type, asset_classes, rest_base_url, ws_url, enabled, priority, health_status)
VALUES ('okx', 'OKX Spot', 'REST_WS', ARRAY['CRYPTO'], 'https://www.okx.com', 'wss://ws.okx.com:8443/ws/v5/public', false, 30, 'UNKNOWN')
ON CONFLICT (code) DO NOTHING;

INSERT INTO market.data_provider_capabilities (provider_id, capability, enabled)
SELECT id, capability, true
FROM market.data_providers
CROSS JOIN (
  VALUES ('SYMBOLS'), ('QUOTE'), ('SNAPSHOT'), ('CANDLES'), ('ORDER_BOOK'), ('TRADES')
) AS caps(capability)
WHERE code = 'okx'
ON CONFLICT (provider_id, capability) DO NOTHING;
