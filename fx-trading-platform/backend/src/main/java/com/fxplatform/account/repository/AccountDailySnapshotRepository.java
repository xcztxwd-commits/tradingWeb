package com.fxplatform.account.repository;

import com.fxplatform.account.entity.AccountDailySnapshotEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface AccountDailySnapshotRepository extends FxBaseMapper<AccountDailySnapshotEntity> {

  @Insert("""
      INSERT INTO core.account_daily_snapshots (
        id,
        account_id,
        wallet_type,
        asset,
        balance,
        equity,
        used_margin,
        free_margin,
        open_pnl,
        realized_pnl,
        snapshot_date
      ) VALUES (
        #{id},
        #{accountId},
        #{walletType},
        #{asset},
        #{balance},
        #{equity},
        #{usedMargin},
        #{freeMargin},
        #{openPnl},
        #{realizedPnl},
        #{snapshotDate}
      )
      ON CONFLICT (account_id, wallet_type, asset, snapshot_date) DO UPDATE SET
        balance = EXCLUDED.balance,
        equity = EXCLUDED.equity,
        used_margin = EXCLUDED.used_margin,
        free_margin = EXCLUDED.free_margin,
        open_pnl = EXCLUDED.open_pnl,
        realized_pnl = EXCLUDED.realized_pnl,
        created_at = now()
      """)
  void upsert(AccountDailySnapshotEntity snapshot);
}
