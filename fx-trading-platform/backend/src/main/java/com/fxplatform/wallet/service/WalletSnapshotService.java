package com.fxplatform.wallet.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.AccountDailySnapshotEntity;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.AccountDailySnapshotRepository;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.entity.WalletDailySnapshotEntity;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.repository.WalletDailySnapshotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletSnapshotService {

  private static final int SCALE = 8;

  private final TradingAccountRepository accountRepository;
  private final AccountDailySnapshotRepository accountDailySnapshotRepository;
  private final WalletBalanceRepository walletBalanceRepository;
  private final WalletDailySnapshotRepository walletDailySnapshotRepository;
  private final PositionRepository positionRepository;

  @Transactional
  public void snapshot(LocalDate snapshotDate) {
    for (TradingAccountEntity account : accountRepository.findAll()) {
      accountDailySnapshotRepository.upsert(accountSnapshot(account, snapshotDate));
    }
    for (WalletBalanceEntity balance : walletBalanceRepository.findAll()) {
      walletDailySnapshotRepository.upsert(walletSnapshot(balance, snapshotDate));
    }
  }

  private AccountDailySnapshotEntity accountSnapshot(TradingAccountEntity account, LocalDate snapshotDate) {
    BigDecimal openPnl = positionRepository
        .findByAccountIdAndStatusOrderByOpenedAtDesc(account.getId(), PositionStatus.OPEN)
        .stream()
        .map(PositionEntity::getFloatingPnl)
        .map(WalletSnapshotService::money)
        .reduce(zero(), BigDecimal::add);
    BigDecimal realizedPnl = positionRepository.findByAccountIdOrderByOpenedAtDesc(account.getId())
        .stream()
        .map(PositionEntity::getRealizedPnl)
        .map(WalletSnapshotService::money)
        .reduce(zero(), BigDecimal::add);

    AccountDailySnapshotEntity snapshot = new AccountDailySnapshotEntity();
    snapshot.setAccountId(account.getId());
    snapshot.setWalletType("MARGIN");
    snapshot.setAsset(asset(account.getBaseCurrency()));
    snapshot.setBalance(money(account.getBalance()));
    snapshot.setEquity(money(account.getEquity()));
    snapshot.setUsedMargin(money(account.getUsedMargin()));
    snapshot.setFreeMargin(money(account.getFreeMargin()));
    snapshot.setOpenPnl(money(openPnl));
    snapshot.setRealizedPnl(money(realizedPnl));
    snapshot.setSnapshotDate(snapshotDate);
    return snapshot;
  }

  private WalletDailySnapshotEntity walletSnapshot(WalletBalanceEntity balance, LocalDate snapshotDate) {
    WalletDailySnapshotEntity snapshot = new WalletDailySnapshotEntity();
    snapshot.setAccountId(balance.getAccountId());
    snapshot.setWalletType(balance.getWalletType());
    snapshot.setAsset(balance.getAsset());
    snapshot.setTotal(money(balance.getTotal()));
    snapshot.setAvailable(money(balance.getAvailable()));
    snapshot.setLocked(money(balance.getLocked()));
    snapshot.setSnapshotDate(snapshotDate);
    return snapshot;
  }

  private static String asset(String asset) {
    return asset == null || asset.isBlank() ? "USD" : asset.trim().toUpperCase();
  }

  private static BigDecimal money(BigDecimal value) {
    return orZero(value).setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
  }
}
