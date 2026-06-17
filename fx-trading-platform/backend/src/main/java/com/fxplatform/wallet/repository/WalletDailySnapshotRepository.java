package com.fxplatform.wallet.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.wallet.entity.WalletDailySnapshotEntity;
import org.apache.ibatis.annotations.Insert;

public interface WalletDailySnapshotRepository extends FxBaseMapper<WalletDailySnapshotEntity> {

  @Insert("""
      INSERT INTO core.wallet_daily_snapshots (
        id,
        account_id,
        wallet_type,
        asset,
        total,
        available,
        locked,
        snapshot_date
      ) VALUES (
        #{id},
        #{accountId},
        #{walletType},
        #{asset},
        #{total},
        #{available},
        #{locked},
        #{snapshotDate}
      )
      ON CONFLICT (account_id, wallet_type, asset, snapshot_date) DO UPDATE SET
        total = EXCLUDED.total,
        available = EXCLUDED.available,
        locked = EXCLUDED.locked,
        created_at = now()
      """)
  void upsert(WalletDailySnapshotEntity snapshot);
}
