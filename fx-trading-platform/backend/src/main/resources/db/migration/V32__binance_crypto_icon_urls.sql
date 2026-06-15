UPDATE market.symbols
SET icon_url = updates.icon_url,
    updated_at = now()
FROM (
  VALUES
    ('BTCUSDT', 'https://bin.bnbstatic.com/image/admin_mgs_image_upload/20201110/87496d50-2408-43e1-ad4c-78b47b448a6a.png'),
    ('ETHUSDT', 'https://bin.bnbstatic.com/image/admin_mgs_image_upload/20201110/3a8c9fe6-2a76-4ace-aa07-415d994de6f0.png'),
    ('SOLUSDT', 'https://bin.bnbstatic.com/image/admin_mgs_image_upload/20230404/b2f0c70f-4fb2-4472-9fe7-480ad1592421.png'),
    ('XRPUSDT', 'https://bin.bnbstatic.com/image/admin_mgs_image_upload/20201110/4766a9cc-8545-4c2b-bfa4-cad2be91c135.png')
) AS updates(symbol, icon_url)
WHERE market.symbols.symbol = updates.symbol
  AND market.symbols.provider = 'binance';
