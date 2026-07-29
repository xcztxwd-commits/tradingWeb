import { mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const STATUS = Object.freeze({
  PASS: "PASS",
  CONDITIONAL: "CONDITIONAL",
  FAIL: "FAIL",
  NOT_APPLICABLE: "NOT_APPLICABLE",
});

const POW10 = [1n];

function pow10(scale) {
  while (POW10.length <= scale) {
    POW10.push(POW10.at(-1) * 10n);
  }
  return POW10[scale];
}

function normalizeDecimal(value) {
  if (value.int === 0n) {
    return { int: 0n, scale: 0 };
  }
  let { int, scale } = value;
  while (scale > 0 && int % 10n === 0n) {
    int /= 10n;
    scale -= 1;
  }
  return { int, scale };
}

export function parseDecimal(value) {
  if (value && typeof value === "object" && typeof value.int === "bigint") {
    return normalizeDecimal({ int: value.int, scale: value.scale });
  }
  if (value == null || value === "") {
    return { int: 0n, scale: 0 };
  }
  let text = String(value).trim();
  if (!text) {
    return { int: 0n, scale: 0 };
  }
  let sign = 1n;
  if (text.startsWith("-")) {
    sign = -1n;
    text = text.slice(1);
  } else if (text.startsWith("+")) {
    text = text.slice(1);
  }
  const exponentMatch = text.match(/^(.+?)[eE]([+-]?\d+)$/);
  let exponent = 0;
  if (exponentMatch) {
    text = exponentMatch[1];
    exponent = Number(exponentMatch[2]);
  }
  if (!/^\d*(?:\.\d*)?$/.test(text) || text === ".") {
    throw new Error(`无效十进制数：${value}`);
  }
  const [whole = "0", fraction = ""] = text.split(".");
  let digits = `${whole || "0"}${fraction}`.replace(/^0+(?=\d)/, "");
  let scale = fraction.length - exponent;
  if (scale < 0) {
    digits += "0".repeat(-scale);
    scale = 0;
  }
  return normalizeDecimal({ int: sign * BigInt(digits || "0"), scale });
}

function alignDecimals(leftValue, rightValue) {
  const left = parseDecimal(leftValue);
  const right = parseDecimal(rightValue);
  const scale = Math.max(left.scale, right.scale);
  return {
    left: left.int * pow10(scale - left.scale),
    right: right.int * pow10(scale - right.scale),
    scale,
  };
}

export function addDecimal(leftValue, rightValue) {
  const aligned = alignDecimals(leftValue, rightValue);
  return normalizeDecimal({ int: aligned.left + aligned.right, scale: aligned.scale });
}

export function subtractDecimal(leftValue, rightValue) {
  const right = parseDecimal(rightValue);
  return addDecimal(leftValue, { int: -right.int, scale: right.scale });
}

export function multiplyDecimal(leftValue, rightValue) {
  const left = parseDecimal(leftValue);
  const right = parseDecimal(rightValue);
  return normalizeDecimal({ int: left.int * right.int, scale: left.scale + right.scale });
}

export function divideDecimal(leftValue, rightValue, scale = 16) {
  const left = parseDecimal(leftValue);
  const right = parseDecimal(rightValue);
  if (right.int === 0n) {
    throw new Error("十进制除数不能为零");
  }
  const negative = (left.int < 0n) !== (right.int < 0n);
  const leftAbs = left.int < 0n ? -left.int : left.int;
  const rightAbs = right.int < 0n ? -right.int : right.int;
  const numerator = leftAbs * pow10(scale + right.scale);
  const denominator = rightAbs * pow10(left.scale);
  let quotient = numerator / denominator;
  const remainder = numerator % denominator;
  if (remainder * 2n >= denominator) {
    quotient += 1n;
  }
  return normalizeDecimal({ int: negative ? -quotient : quotient, scale });
}

function scaledInteger(value, scale = 8) {
  const decimal = parseDecimal(value);
  if (decimal.scale <= scale) {
    return decimal.int * pow10(scale - decimal.scale);
  }
  const negative = decimal.int < 0n;
  const absolute = negative ? -decimal.int : decimal.int;
  const divisor = pow10(decimal.scale - scale);
  let quotient = absolute / divisor;
  const remainder = absolute % divisor;
  if (remainder * 2n >= divisor) {
    quotient += 1n;
  }
  return negative ? -quotient : quotient;
}

export function decimalToFixed(value, scale = 8) {
  const scaled = scaledInteger(value, scale);
  const negative = scaled < 0n;
  const absolute = negative ? -scaled : scaled;
  if (scale === 0) {
    return `${negative ? "-" : ""}${absolute}`;
  }
  const digits = absolute.toString().padStart(scale + 1, "0");
  return `${negative ? "-" : ""}${digits.slice(0, -scale)}.${digits.slice(-scale)}`;
}

function compareDecimal(leftValue, rightValue, scale = 8) {
  const left = scaledInteger(leftValue, scale);
  const right = scaledInteger(rightValue, scale);
  return left === right ? 0 : left < right ? -1 : 1;
}

function absoluteDecimal(value) {
  const decimal = parseDecimal(value);
  return decimal.int < 0n ? { int: -decimal.int, scale: decimal.scale } : decimal;
}

function minimumDecimal(leftValue, rightValue) {
  return compareDecimal(leftValue, rightValue, 16) <= 0 ? parseDecimal(leftValue) : parseDecimal(rightValue);
}

function negateDecimal(value) {
  const decimal = parseDecimal(value);
  return { int: -decimal.int, scale: decimal.scale };
}

function asArray(value) {
  return Array.isArray(value) ? value : value == null ? [] : [value];
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function finalCheckpoint(actual) {
  return asArray(actual?.checkpoints).at(-1) ?? { snapshot: {} };
}

function actionAt(scenario, checkpoint, checkpointIndex) {
  return asArray(scenario.actions).find((action) => action.parameters?.actionId === checkpoint.actionId)
    ?? asArray(scenario.actions)[checkpointIndex]
    ?? {};
}

function marketAt(scenario, actionIndex) {
  const steps = asArray(scenario.priceSteps);
  return steps.find((step) => Number(step.sequence) === Number(actionIndex))
    ?? steps[Math.min(Math.max(Number(actionIndex), 0), Math.max(steps.length - 1, 0))]
    ?? {};
}

function feeRateFor(scenario, action, actionIndex, trade) {
  const parameters = action?.parameters ?? {};
  const priorParameters = asArray(scenario.actions)
    .slice(0, actionIndex + 1)
    .map((candidate) => candidate.parameters ?? {})
    .reverse();
  const candidates = [parameters, ...priorParameters];
  const roleField = trade.liquidityRole === "MAKER" ? "makerFeeRate" : "takerFeeRate";
  for (const candidate of candidates) {
    if (candidate[roleField] != null) {
      return candidate[roleField];
    }
    if (candidate.feeRate != null) {
      return candidate.feeRate;
    }
  }
  return 0;
}

function makeNumericCheck({ kind, ref, formula, actual, calculated, exchange = "BOTH", note = "" }) {
  return {
    kind,
    ref,
    formula,
    actual: decimalToFixed(actual, 8),
    calculated: decimalToFixed(calculated, 8),
    difference: decimalToFixed(subtractDecimal(actual, calculated), 8),
    equal: compareDecimal(actual, calculated, 8) === 0,
    exchange,
    note,
  };
}

function makeBooleanCheck(kind, ref, actual, expected, note = "") {
  return {
    kind,
    ref,
    formula: note || `${kind}: actual=${actual}, expected=${expected}`,
    actual: String(actual),
    calculated: String(expected),
    difference: actual === expected ? "相同" : "不同",
    equal: actual === expected,
    exchange: "BOTH",
    note,
  };
}

function stateFromPosition(position) {
  return {
    side: position?.side,
    quantity: parseDecimal(position?.quantity ?? 0),
    averageEntry: parseDecimal(position?.averageEntry ?? 0),
  };
}

function initialPositionStates(scenario) {
  const states = new Map();
  const initial = scenario.initialPosition;
  if (typeof initial !== "string" || !initial || initial === "NONE") {
    return states;
  }
  for (const match of initial.matchAll(/(LONG|SHORT)=([+-]?\d+(?:\.\d+)?)@([+-]?\d+(?:\.\d+)?)/g)) {
    const direction = match[1];
    const slot = scenario.positionMode === "HEDGE"
      ? `BTCUSDT-PERP:${direction}`
      : "BTCUSDT-PERP:BOTH";
    states.set(slot, {
      side: direction === "LONG" ? "BUY" : "SELL",
      quantity: parseDecimal(match[2]),
      averageEntry: parseDecimal(match[3]),
    });
  }
  return states;
}

function positionSlotForTrade(trade, positions, states) {
  if (trade.positionSide && trade.positionSide !== "BOTH") {
    const matching = [...states.keys()].find((slot) => slot.endsWith(`:${trade.positionSide}`))
      ?? asArray(positions).find((position) => position.positionSide === trade.positionSide)?.slot;
    return matching ?? `BTCUSDT-PERP:${trade.positionSide}`;
  }
  return [...states.keys()].find((slot) => slot.endsWith(":BOTH"))
    ?? asArray(positions).find((position) => position.positionSide === "BOTH")?.slot
    ?? "BTCUSDT-PERP:BOTH";
}

function applyLinearTrade(stateValue, trade) {
  const state = stateValue ?? { side: null, quantity: parseDecimal(0), averageEntry: parseDecimal(0) };
  const tradeQuantity = parseDecimal(trade.quantity);
  const tradePrice = parseDecimal(trade.price);
  if (compareDecimal(state.quantity, 0, 16) === 0 || !state.side) {
    return {
      realized: parseDecimal(0),
      state: { side: trade.side, quantity: tradeQuantity, averageEntry: tradePrice },
    };
  }
  if (state.side === trade.side) {
    const oldValue = multiplyDecimal(state.quantity, state.averageEntry);
    const addedValue = multiplyDecimal(tradeQuantity, tradePrice);
    const quantity = addDecimal(state.quantity, tradeQuantity);
    return {
      realized: parseDecimal(0),
      state: {
        side: state.side,
        quantity,
        averageEntry: divideDecimal(addDecimal(oldValue, addedValue), quantity, 16),
      },
    };
  }
  const closed = minimumDecimal(state.quantity, tradeQuantity);
  const priceDifference = state.side === "BUY"
    ? subtractDecimal(tradePrice, state.averageEntry)
    : subtractDecimal(state.averageEntry, tradePrice);
  const realized = multiplyDecimal(priceDifference, closed);
  const remainingExisting = subtractDecimal(state.quantity, closed);
  const remainingTrade = subtractDecimal(tradeQuantity, closed);
  if (compareDecimal(remainingExisting, 0, 16) > 0) {
    return {
      realized,
      state: { side: state.side, quantity: remainingExisting, averageEntry: state.averageEntry },
    };
  }
  if (compareDecimal(remainingTrade, 0, 16) > 0) {
    return {
      realized,
      state: { side: trade.side, quantity: remainingTrade, averageEntry: tradePrice },
    };
  }
  return {
    realized,
    state: { side: null, quantity: parseDecimal(0), averageEntry: parseDecimal(0) },
  };
}

function refs(items) {
  return new Set(asArray(items).map((item) => item.ref ?? `${item.sequence}:${item.type}`));
}

function newItems(current, previous) {
  const prior = refs(previous);
  return asArray(current).filter((item) => !prior.has(item.ref ?? `${item.sequence}:${item.type}`));
}

function summarizeObject(value) {
  return Object.entries(value ?? {})
    .filter(([, candidate]) => candidate != null && candidate !== "" && candidate !== false)
    .map(([key, candidate]) => `${key}=${Array.isArray(candidate) ? JSON.stringify(candidate) : candidate}`)
    .join("，");
}

function describeStep(scenario, checkpoint, checkpointIndex, previousSnapshot) {
  const action = actionAt(scenario, checkpoint, checkpointIndex);
  const snapshot = checkpoint.snapshot ?? {};
  const market = marketAt(scenario, checkpoint.actionIndex ?? checkpointIndex + 1);
  const orders = newItems(snapshot.orders, previousSnapshot?.orders);
  const trades = newItems(snapshot.trades, previousSnapshot?.trades);
  const ledgers = newItems(snapshot.ledger, previousSnapshot?.ledger);
  const request = {
    type: action.type,
    direction: action.direction,
    quantity: action.quantity,
    ...action.parameters,
  };
  return {
    step: checkpoint.actionIndex ?? checkpointIndex + 1,
    actionId: checkpoint.actionId,
    plainLanguage: `第 ${checkpoint.actionIndex ?? checkpointIndex + 1} 步执行 ${action.type ?? "未知动作"}${action.direction ? `（${action.direction}）` : ""}${action.quantity != null ? `，数量 ${action.quantity} ${action.parameters?.quantityUnit ?? scenario.quantityUnit}` : ""}。`,
    market: `bid=${market.bid ?? "-"}，ask=${market.ask ?? "-"}，last=${market.last ?? "-"}，mark=${market.mark ?? "-"}，index=${market.index ?? "-"}，source=${market.source ?? "-"}`,
    request: summarizeObject(request),
    actualChange: [
      `新增订单 ${orders.length} 笔：${orders.map((order) => `${order.ref}/${order.side}/${order.type}/${order.status}/qty=${order.quantity}/filled=${order.filledQuantity}/fee=${order.fee} ${order.feeAsset}`).join("；") || "无"}`,
      `新增成交 ${trades.length} 笔：${trades.map((trade) => `${trade.ref}/${trade.side}/qty=${trade.quantity}/px=${trade.price}/notional=${trade.quoteNotional}/fee=${trade.fee} ${trade.feeAsset}/realized=${trade.realizedPnl}`).join("；") || "无"}`,
      `新增账本 ${ledgers.length} 条：${ledgers.map((entry) => `${entry.sequence}/${entry.type}/${entry.amount} ${entry.asset}`).join("；") || "无"}`,
      checkpoint.failure ? `失败=${checkpoint.failure.code}：${checkpoint.failure.message ?? ""}` : "本步无失败",
    ].join("\n"),
  };
}

function summarizeActual(actual) {
  const checkpoint = finalCheckpoint(actual);
  const snapshot = checkpoint.snapshot ?? {};
  const positions = asArray(snapshot.positions).map((position) =>
    `${position.slot}/${position.status}/${position.side}/qty=${position.quantity}/entry=${position.averageEntry}/mark=${position.markPrice}/realized=${position.realizedPnl}/unrealized=${position.unrealizedPnl}/funding=${position.fundingPnl}/IM=${position.initialMargin}/MM=${position.maintenanceMargin}`
  );
  const wallets = asArray(snapshot.wallets).map((wallet) =>
    `${wallet.walletType}:${wallet.asset}/total=${wallet.total}/available=${wallet.available}/locked=${wallet.locked}`
  );
  return [
    `最终订单=${asArray(snapshot.orders).length}，成交=${asArray(snapshot.trades).length}，仓位=${asArray(snapshot.positions).length}，账本=${asArray(snapshot.ledger).length}，保护单=${asArray(snapshot.protections).length}，事件=${asArray(snapshot.events).length}`,
    `仓位：${positions.join("；") || "无"}`,
    `钱包：${wallets.join("；") || "无"}`,
    `账户：balance=${snapshot.account?.balance ?? "-"}，equity=${snapshot.account?.equity ?? "-"}，usedMargin=${snapshot.account?.usedMargin ?? "-"}，freeMargin=${snapshot.account?.freeMargin ?? "-"}，maintenanceMargin=${snapshot.account?.maintenanceMargin ?? "-"}，bankruptcyShortfall=${snapshot.account?.bankruptcyShortfall ?? "-"}`,
    checkpoint.failure ? `最终失败：${checkpoint.failure.code}：${checkpoint.failure.message ?? ""}` : "最终结果：成功路径，无 failure",
  ].join("\n");
}

function ruleIdsForScenario(scenario) {
  if (scenario.productType === "CRYPTO_SPOT") {
    return [
      "BINANCE_SPOT_FILTERS",
      "BINANCE_SPOT_MARKET_QUANTITY",
      "BINANCE_SPOT_COMMISSION_RECEIVED_ASSET",
      "OKX_SPOT_TGT_CCY_AND_AMEND",
      "OKX_SPOT_FEE_TYPE",
      ...(asArray(scenario.actions).some((action) => action.type === "CREATE_OCO") ? ["BINANCE_SPOT_OCO"] : []),
    ];
  }
  return [
    "BINANCE_USDM_ORDER_POSITION_MODE",
    "BINANCE_USDM_FILTERS",
    "BINANCE_USDM_MARK_PNL_FUNDING",
    "OKX_SWAP_CONTRACT_QUANTITY",
    "OKX_SWAP_POSITION_MODE_REDUCE_ONLY",
    "OKX_SWAP_TRADING_FEE",
    "OKX_SWAP_MARK_AND_PNL",
    "OKX_SWAP_MARGIN",
    "OKX_SWAP_FUNDING",
  ];
}

function isPlatformOnlyScenario(scenario) {
  return /RACE|ROLLBACK|IDEMPOT|CLIENT_ORDER|PROVIDER|MARKET_DATA|ADMIN|PERMISSION|INCOMPLETE|LOCK_WAIT|PARTIAL_FILL|TRANSACTION|LEDGER|TRADE_ROLLBACK|BATCH_PARTIAL/i.test(scenario.caseId);
}

function auditOneScenario(scenario, actual, caseNumber) {
  const numericChecks = [];
  const binanceDifferences = [];
  const okxDifferences = [];
  const binanceConditions = [];
  const okxConditions = [];
  const operationSteps = [];
  let previousSnapshot = null;
  let sawTrade = false;
  let sawSpotBuy = false;

  for (const [checkpointIndex, checkpoint] of asArray(actual.checkpoints).entries()) {
    const snapshot = checkpoint.snapshot ?? {};
    const action = actionAt(scenario, checkpoint, checkpointIndex);
    const newTrades = newItems(snapshot.trades, previousSnapshot?.trades);
    const newLedger = newItems(snapshot.ledger, previousSnapshot?.ledger);
    const states = previousSnapshot
      ? new Map(asArray(previousSnapshot.positions).map((position) => [position.slot, stateFromPosition(position)]))
      : initialPositionStates(scenario);
    operationSteps.push(describeStep(scenario, checkpoint, checkpointIndex, previousSnapshot));

    for (const trade of newTrades) {
      sawTrade = true;
      const contractFactor = scenario.productType === "LINEAR_PERP" ? "1" : "1";
      const calculatedNotional = multiplyDecimal(multiplyDecimal(trade.quantity, trade.price), contractFactor);
      numericChecks.push(makeNumericCheck({
        kind: "TRADE_NOTIONAL",
        ref: trade.ref,
        formula: scenario.productType === "LINEAR_PERP"
          ? "quantity × price × normalizedContractValue(1)"
          : "baseQuantity × fillPrice",
        actual: trade.quoteNotional,
        calculated: calculatedNotional,
      }));
      const rate = feeRateFor(scenario, action, checkpointIndex, trade);
      const calculatedFee = multiplyDecimal(calculatedNotional, rate);
      numericChecks.push(makeNumericCheck({
        kind: "TRADE_FEE",
        ref: trade.ref,
        formula: `${decimalToFixed(calculatedNotional, 8)} × feeRate(${rate})`,
        actual: trade.fee,
        calculated: calculatedFee,
      }));

      const ledgerForTrade = newLedger.filter((entry) => entry.reference === trade.ref);
      const feeLedger = ledgerForTrade.find((entry) => entry.type === "TRADE_FEE");
      if (feeLedger) {
        numericChecks.push(makeNumericCheck({
          kind: "LEDGER_TRADE_FEE",
          ref: trade.ref,
          formula: "TRADE_FEE ledger amount = -trade fee",
          actual: feeLedger.amount,
          calculated: negateDecimal(trade.fee),
        }));
      }

      if (scenario.productType === "CRYPTO_SPOT") {
        if (trade.side === "BUY") {
          sawSpotBuy = true;
          const expectedBaseFee = multiplyDecimal(trade.quantity, rate);
          if (trade.feeAsset !== "BTC") {
            binanceDifferences.push({
              ruleId: "BINANCE_SPOT_COMMISSION_RECEIVED_ASSET",
              ref: trade.ref,
              actual: `${decimalToFixed(trade.fee, 8)} ${trade.feeAsset}`,
              official: `${decimalToFixed(expectedBaseFee, 8)} BTC（未使用 BNB 抵扣）`,
              difference: `实际完整入账 ${decimalToFixed(trade.quantity, 8)} BTC；Binance 默认应净入账 ${decimalToFixed(subtractDecimal(trade.quantity, expectedBaseFee), 8)} BTC。`,
            });
          }
          if (trade.feeAsset === "USDT") {
            okxConditions.push({
              ruleId: "OKX_SPOT_FEE_TYPE",
              ref: trade.ref,
              condition: "必须在该子账户设置 feeType=1（用计价币支付现货买入手续费）。",
            });
          } else if (trade.feeAsset !== "BTC") {
            okxDifferences.push({
              ruleId: "OKX_SPOT_FEE_TYPE",
              ref: trade.ref,
              actual: trade.feeAsset,
              official: "feeType=0 时 BTC；feeType=1 时 USDT",
              difference: "手续费资产无法映射 OKX 两种标准模式。",
            });
          }
          const debit = ledgerForTrade.find((entry) => entry.type === "SPOT_BUY_DEBIT");
          const credit = ledgerForTrade.find((entry) => entry.type === "SPOT_BUY_CREDIT");
          if (debit) {
            numericChecks.push(makeNumericCheck({
              kind: "SPOT_BUY_DEBIT",
              ref: trade.ref,
              formula: "USDT debit = -quote notional",
              actual: debit.amount,
              calculated: negateDecimal(calculatedNotional),
            }));
          }
          if (credit) {
            numericChecks.push(makeNumericCheck({
              kind: "SPOT_BUY_CREDIT",
              ref: trade.ref,
              formula: "project actual base credit = gross fill quantity",
              actual: credit.amount,
              calculated: trade.quantity,
            }));
          }
        } else {
          const debit = ledgerForTrade.find((entry) => entry.type === "SPOT_SELL_DEBIT");
          const credit = ledgerForTrade.find((entry) => entry.type === "SPOT_SELL_CREDIT");
          if (trade.feeAsset !== "USDT") {
            binanceDifferences.push({
              ruleId: "BINANCE_SPOT_COMMISSION_RECEIVED_ASSET",
              ref: trade.ref,
              actual: trade.feeAsset,
              official: "USDT",
              difference: "卖出手续费没有从收到的计价币扣除。",
            });
            okxDifferences.push({
              ruleId: "OKX_SPOT_FEE_TYPE",
              ref: trade.ref,
              actual: trade.feeAsset,
              official: "USDT",
              difference: "OKX 现货卖出手续费应从收到的计价币扣除。",
            });
          }
          if (debit) {
            numericChecks.push(makeNumericCheck({
              kind: "SPOT_SELL_DEBIT",
              ref: trade.ref,
              formula: "BTC debit = -sold base quantity",
              actual: debit.amount,
              calculated: negateDecimal(trade.quantity),
            }));
          }
          if (credit) {
            numericChecks.push(makeNumericCheck({
              kind: "SPOT_SELL_CREDIT",
              ref: trade.ref,
              formula: "USDT gross credit = quote notional",
              actual: credit.amount,
              calculated: calculatedNotional,
            }));
          }
        }
      } else {
        const slot = positionSlotForTrade(trade, previousSnapshot?.positions, states);
        const applied = applyLinearTrade(states.get(slot), trade);
        states.set(slot, applied.state);
        numericChecks.push(makeNumericCheck({
          kind: "REALIZED_PNL",
          ref: trade.ref,
          formula: "long: (close−entry)×closedQty；short: (entry−close)×closedQty",
          actual: trade.realizedPnl,
          calculated: applied.realized,
        }));
      }
    }

    if (scenario.productType === "LINEAR_PERP") {
      const parameters = action.parameters ?? {};
      const mmr = parameters.maintenanceMarginRate
        ?? asArray(scenario.actions).find((candidate) => candidate.parameters?.maintenanceMarginRate != null)?.parameters.maintenanceMarginRate
        ?? 0;
      for (const position of asArray(snapshot.positions).filter((candidate) => compareDecimal(candidate.quantity, 0, 8) > 0)) {
        const notional = multiplyDecimal(position.quantity, position.markPrice);
        const directionDifference = position.side === "SELL"
          ? subtractDecimal(position.averageEntry, position.markPrice)
          : subtractDecimal(position.markPrice, position.averageEntry);
        const unrealized = multiplyDecimal(directionDifference, position.quantity);
        numericChecks.push(makeNumericCheck({
          kind: "POSITION_NOTIONAL",
          ref: `${checkpoint.actionId}:${position.slot}`,
          formula: "quantity × markPrice × normalizedContractValue(1)",
          actual: position.notional,
          calculated: notional,
        }));
        numericChecks.push(makeNumericCheck({
          kind: "UNREALIZED_PNL",
          ref: `${checkpoint.actionId}:${position.slot}`,
          formula: position.side === "SELL" ? "(entry−mark)×quantity" : "(mark−entry)×quantity",
          actual: position.unrealizedPnl,
          calculated: unrealized,
        }));
        numericChecks.push(makeNumericCheck({
          kind: "MAINTENANCE_MARGIN",
          ref: `${checkpoint.actionId}:${position.slot}`,
          formula: `notional(mark) × scenarioMMR(${mmr})`,
          actual: position.maintenanceMargin,
          calculated: multiplyDecimal(notional, mmr),
          note: "公式可复核；真实交易所 MMR 必须按实时风险档位注入。",
        }));
        const okxInitialMarginBase = position.marginMode === "ISOLATED"
          ? multiplyDecimal(position.quantity, position.averageEntry)
          : notional;
        numericChecks.push(makeNumericCheck({
          kind: "OKX_INITIAL_MARGIN",
          ref: `${checkpoint.actionId}:${position.slot}`,
          formula: position.marginMode === "ISOLATED"
            ? "ctVal×contracts×ctMult×averageEntry/leverage"
            : "ctVal×contracts×ctMult×markPrice/leverage",
          actual: position.initialMargin,
          calculated: divideDecimal(okxInitialMarginBase, position.leverage, 16),
          exchange: "OKX",
          note: position.marginMode === "ISOLATED" ? "OKX isolated 使用开仓均价。" : "OKX cross 随标记价变化。",
        }));
      }

      if (action.type === "SETTLE_FUNDING") {
        const rate = action.parameters?.fundingRate ?? 0;
        let calculatedFunding = parseDecimal(0);
        for (const position of asArray(previousSnapshot?.positions).filter((candidate) => compareDecimal(candidate.quantity, 0, 8) > 0)) {
          const mark = marketAt(scenario, checkpoint.actionIndex)?.mark ?? position.markPrice;
          const funding = multiplyDecimal(multiplyDecimal(position.quantity, mark), rate);
          calculatedFunding = addDecimal(
            calculatedFunding,
            position.side === "SELL" ? funding : negateDecimal(funding),
          );
        }
        const actualFunding = newLedger
          .filter((entry) => entry.type === "FUNDING_FEE")
          .reduce((sum, entry) => addDecimal(sum, entry.amount), parseDecimal(0));
        numericChecks.push(makeNumericCheck({
          kind: "FUNDING",
          ref: checkpoint.actionId,
          formula: "Σ(positionValue(mark) × rate × direction)，positive rate: long pays, short receives",
          actual: actualFunding,
          calculated: calculatedFunding,
        }));
      }
    }

    previousSnapshot = snapshot;
  }

  const endingFailure = finalCheckpoint(actual).failure?.code ?? "";
  numericChecks.push(makeBooleanCheck(
    scenario.expectedError ? "EXPECTED_REJECTION" : "SUCCESS_PATH",
    scenario.caseId,
    endingFailure,
    scenario.expectedError ?? "",
    scenario.expectedError ? "最终 failure.code 必须等于场景输入的 expectedError。" : "成功场景最终不得出现 failure。",
  ));

  for (const check of numericChecks.filter((candidate) => !candidate.equal)) {
    if (check.exchange === "OKX") {
      okxDifferences.push({
        ruleId: check.kind === "OKX_INITIAL_MARGIN" ? "OKX_SWAP_MARGIN" : "OKX_SWAP_MARK_AND_PNL",
        ref: check.ref,
        actual: check.actual,
        official: check.calculated,
        difference: `${check.formula}，差值=${check.difference}`,
      });
    } else {
      const difference = {
        ruleId: scenario.productType === "CRYPTO_SPOT" ? "BINANCE_SPOT_FILTERS" : "BINANCE_USDM_MARK_PNL_FUNDING",
        ref: check.ref,
        actual: check.actual,
        official: check.calculated,
        difference: `${check.formula}，差值=${check.difference}`,
      };
      binanceDifferences.push(difference);
      okxDifferences.push({ ...difference, ruleId: scenario.productType === "CRYPTO_SPOT" ? "OKX_SPOT_TGT_CCY_AND_AMEND" : "OKX_SWAP_MARK_AND_PNL" });
    }
  }

  if (scenario.productType === "CRYPTO_SPOT" && /INSUFFICIENT/i.test(scenario.caseId)) {
    okxConditions.push({
      ruleId: "OKX_SPOT_TGT_CCY_AND_AMEND",
      ref: scenario.caseId,
      condition: "要得到整单余额不足拒绝，OKX 市价单必须发送 banAmend=true；默认会自动缩量。",
    });
  }
  if (scenario.productType === "LINEAR_PERP" && sawTrade) {
    okxConditions.push({
      ruleId: "OKX_SWAP_CONTRACT_QUANTITY",
      ref: scenario.caseId,
      condition: "场景按 ctVal=1、ctMult=1 归一化；真实 OKX sz 必须按 instruments 返回的 ctVal/ctMult 换算。",
    });
  }
  if (isPlatformOnlyScenario(scenario)) {
    const condition = {
      ruleId: "EXCHANGE_DYNAMIC_RISK_AND_FILL",
      ref: scenario.caseId,
      condition: "该场景包含平台事务、权限、并发、行情提供方或幂等语义，交易所公开下单规则只能校验交易部分，平台控制面结果不属于交易所原生算法。",
    };
    binanceConditions.push(condition);
    okxConditions.push(condition);
  }
  if (sawTrade) {
    const condition = {
      ruleId: "EXCHANGE_DYNAMIC_RISK_AND_FILL",
      ref: scenario.caseId,
      condition: "成交价与一次全量成交来自 DEMO 撮合；真实盘口可能滑点、多笔或部分成交。",
    };
    binanceConditions.push(condition);
    okxConditions.push(condition);
  }

  const binanceStatus = binanceDifferences.length > 0
    ? STATUS.FAIL
    : binanceConditions.length > 0
      ? STATUS.CONDITIONAL
      : STATUS.PASS;
  const okxStatus = okxDifferences.length > 0
    ? STATUS.FAIL
    : okxConditions.length > 0
      ? STATUS.CONDITIONAL
      : STATUS.PASS;

  const commonFormulaSummary = numericChecks
    .map((check) => `${check.kind}[${check.ref}]：actual=${check.actual}，官方公式重算=${check.calculated}，${check.equal ? "相等" : `不相等（差 ${check.difference}）`}`)
    .join("\n");
  const binanceMapping = scenario.productType === "CRYPTO_SPOT"
    ? "Spot：BASE→quantity，QUOTE 买入预算→quoteOrderQty；未使用 BNB 时买入手续费扣 BTC、卖出手续费扣 USDT。"
    : "USDⓈ-M：场景归一化 CONTRACTS 先换成 BTC 基础币数量，再发送 quantity；ONE_WAY→positionSide=BOTH，HEDGE→LONG/SHORT。";
  const okxMapping = scenario.productType === "CRYPTO_SPOT"
    ? "Spot：买入 QUOTE→tgtCcy=quote_ccy，卖出 BASE→tgtCcy=base_ccy；严格余额拒绝需 banAmend=true。"
    : "USDT SWAP：请求 sz 单位是 contracts，基础币敞口=sz×ctVal×ctMult；ONE_WAY→net，HEDGE→long/short。";

  const actualResult = summarizeActual(actual);
  const binanceConclusionForReport = binanceStatus === STATUS.FAIL
    ? `不正确：发现 ${binanceDifferences.length} 项与 Binance 官方标准不相等，${binanceDifferences.map((item) => item.difference).join("；")}`
    : binanceStatus === STATUS.CONDITIONAL
      ? `公式结果相等，但有 ${binanceConditions.length} 个实盘条件：${binanceConditions.map((item) => item.condition).join("；")}`
      : "正确：场景实际结果与 Binance 官方公式和可直接映射的下单语义相等。";
  const okxConclusionForReport = okxStatus === STATUS.FAIL
    ? `不正确：发现 ${okxDifferences.length} 项与 OKX 官方标准不相等，${okxDifferences.map((item) => item.difference).join("；")}`
    : okxStatus === STATUS.CONDITIONAL
      ? `公式结果相等，但有 ${okxConditions.length} 个适配条件：${okxConditions.map((item) => item.condition).join("；")}`
      : "正确：场景实际结果与 OKX 官方公式和可直接映射的下单语义相等。";
  const reportOperation = [
    ...operationSteps.map((step) => [
      step.plainLanguage,
      `行情：${step.market}`,
      `完整请求：${step.request}`,
      `实际变化：\n${step.actualChange}`,
    ].join("\n")),
    `最终实际结果：\n${actualResult}`,
  ].join("\n\n");
  const describeDifferences = (items) => items.length === 0
    ? "无"
    : items.map((item, index) => `${index + 1}. rule=${item.ruleId}，ref=${item.ref}，actual=${item.actual}，official=${item.official}，difference=${item.difference}`).join("\n");
  const describeConditions = (items) => items.length === 0
    ? "无"
    : items.map((item, index) => `${index + 1}. rule=${item.ruleId}，ref=${item.ref}，condition=${item.condition}`).join("\n");
  const reportExchangeConclusion = [
    `【Binance】状态=${binanceStatus}\n映射=${binanceMapping}\n结论=${binanceConclusionForReport}\n不相等项：\n${describeDifferences(binanceDifferences)}\n条件：\n${describeConditions(binanceConditions)}`,
    `【OKX】状态=${okxStatus}\n映射=${okxMapping}\n结论=${okxConclusionForReport}\n不相等项：\n${describeDifferences(okxDifferences)}\n条件：\n${describeConditions(okxConditions)}`,
  ].join("\n\n");

  return {
    caseNumber,
    caseId: scenario.caseId,
    productType: scenario.productType,
    expectedPath: scenario.expectedError ? `按场景输入拒绝 ${scenario.expectedError}` : "成功",
    ruleIds: ruleIdsForScenario(scenario),
    operationSteps,
    actualResult,
    actualFinalCheckpoint: finalCheckpoint(actual),
    independentCalculation: commonFormulaSummary,
    reportOperation,
    reportExchangeConclusion,
    numericChecks,
    binance: {
      status: binanceStatus,
      mapping: binanceMapping,
      differences: binanceDifferences,
      conditions: binanceConditions,
      conclusion: binanceStatus === STATUS.FAIL
        ? `不正确：发现 ${binanceDifferences.length} 项与 Binance 官方标准不相等，${binanceDifferences.map((item) => item.difference).join("；")}`
        : binanceStatus === STATUS.CONDITIONAL
          ? `公式结果相等，但有 ${binanceConditions.length} 个实盘条件：${binanceConditions.map((item) => item.condition).join("；")}`
          : "正确：场景实际结果与 Binance 官方公式和可直接映射的下单语义相等。",
    },
    okx: {
      status: okxStatus,
      mapping: okxMapping,
      differences: okxDifferences,
      conditions: okxConditions,
      conclusion: okxStatus === STATUS.FAIL
        ? `不正确：发现 ${okxDifferences.length} 项与 OKX 官方标准不相等，${okxDifferences.map((item) => item.difference).join("；")}`
        : okxStatus === STATUS.CONDITIONAL
          ? `公式结果相等，但有 ${okxConditions.length} 个适配条件：${okxConditions.map((item) => item.condition).join("；")}`
          : "正确：场景实际结果与 OKX 官方公式和可直接映射的下单语义相等。",
    },
    flags: {
      sawTrade,
      sawSpotBuy,
      platformOnlyComponent: isPlatformOnlyScenario(scenario),
    },
  };
}

function summarizeExchange(records, exchange) {
  return {
    pass: records.filter((record) => record[exchange].status === STATUS.PASS).length,
    conditional: records.filter((record) => record[exchange].status === STATUS.CONDITIONAL).length,
    fail: records.filter((record) => record[exchange].status === STATUS.FAIL).length,
    notApplicable: records.filter((record) => record[exchange].status === STATUS.NOT_APPLICABLE).length,
  };
}

export function auditScenarioEvidence(platformRoot) {
  const root = resolve(platformRoot);
  const matrixPath = resolve(root, "docs/testing/spot-perp-scenario-matrix.json");
  const rulePath = resolve(root, "docs/testing/binance-okx-official-rule-register.json");
  const reportPath = resolve(root, "docs/testing/spot-perp-test-report.md");
  const scenarios = readJson(matrixPath);
  const ruleRegister = readJson(rulePath);
  if (!Array.isArray(scenarios) || scenarios.length !== 191 || new Set(scenarios.map((scenario) => scenario.caseId)).size !== 191) {
    throw new Error("场景矩阵必须恰好包含 191 个唯一 caseId");
  }
  const records = scenarios.map((scenario, index) => {
    const actualPath = resolve(root, "backend/target/scenario-artifacts", scenario.caseId, "actual.json");
    const actual = readJson(actualPath);
    if (actual.caseId !== scenario.caseId || asArray(actual.checkpoints).length !== asArray(scenario.actions).length) {
      throw new Error(`${scenario.caseId} 的 actual 证据与矩阵动作不完整对应`);
    }
    return auditOneScenario(scenario, actual, index + 1);
  });
  const reportText = readFileSync(reportPath, "utf8");
  const evidenceGeneratedAt = reportText.match(/Generated \(UTC\):\s*(\S+)/)?.[1] ?? null;
  const evidenceStatus = reportText.match(/Status:\s*\*\*(\w+)\*\*/)?.[1] ?? "UNKNOWN";
  const actualDirectory = resolve(root, "backend/target/scenario-artifacts");
  return {
    platformRoot: root,
    records,
    rules: ruleRegister.rules,
    summary: {
      totalCases: records.length,
      spotCases: records.filter((record) => record.productType === "CRYPTO_SPOT").length,
      perpetualCases: records.filter((record) => record.productType === "LINEAR_PERP").length,
      binance: summarizeExchange(records, "binance"),
      okx: summarizeExchange(records, "okx"),
      numericChecks: records.flatMap((record) => record.numericChecks).length,
      numericMismatches: records.flatMap((record) => record.numericChecks).filter((check) => !check.equal).length,
      evidenceGeneratedAt,
      evidenceStatus,
      matrixModifiedAt: statSync(matrixPath).mtime.toISOString(),
      actualDirectoryModifiedAt: statSync(actualDirectory).mtime.toISOString(),
    },
  };
}

export function buildValidationDocument(audit, generatedAt = new Date().toISOString()) {
  return {
    schemaVersion: 1,
    title: "191 个现货与永续场景 Binance/OKX 官方语义独立复核",
    generatedAt,
    methodology: {
      independence: "只读取场景矩阵、实际执行快照和官方规则登记表；所有理论值由本审计器用 BigInt 十进制定点重新计算。",
      decimalPolicy: "金额、价格、数量和盈亏统一按 8 位小数 HALF_UP 比较。",
      exchangeBoundary: "不连接真实交易所；动态费率、合约面值、风险档位和行情取场景输入或明确标为条件。",
      status: {
        PASS: "公式和可直接映射语义相等。",
        CONDITIONAL: "公式相等，但需要 feeType、banAmend、ctVal/ctMult、真实盘口等适配条件。",
        FAIL: "至少一项实际数值或资产语义与官方标准不相等。",
        NOT_APPLICABLE: "该交易所/产品不适用。",
      },
    },
    summary: audit.summary,
    rules: audit.rules,
    records: audit.records,
  };
}

function cliArguments(argv) {
  const outputIndex = argv.indexOf("--output");
  if (outputIndex < 0 || !argv[outputIndex + 1]) {
    throw new Error("用法：node scripts/validate-binance-okx-scenario-semantics.mjs --output <validation.json>");
  }
  return { output: resolve(argv[outputIndex + 1]) };
}

const isDirectRun = process.argv[1]
  && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;

if (isDirectRun) {
  const platformRoot = dirname(dirname(fileURLToPath(import.meta.url)));
  const { output } = cliArguments(process.argv.slice(2));
  const document = buildValidationDocument(auditScenarioEvidence(platformRoot));
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(document, null, 2)}\n`, "utf8");
  const mismatchKinds = Object.fromEntries(
    Object.entries(document.records
      .flatMap((record) => record.numericChecks)
      .filter((check) => !check.equal)
      .reduce((counts, check) => ({ ...counts, [check.kind]: (counts[check.kind] ?? 0) + 1 }), {}))
      .sort((left, right) => right[1] - left[1]),
  );
  process.stdout.write(`Generated ${output}\n`);
  process.stdout.write(`Binance ${JSON.stringify(document.summary.binance)}\n`);
  process.stdout.write(`OKX ${JSON.stringify(document.summary.okx)}\n`);
  process.stdout.write(`Numeric mismatches ${JSON.stringify(mismatchKinds)}\n`);
  process.stdout.write(`Binance FAIL cases ${document.records.filter((record) => record.binance.status === STATUS.FAIL).map((record) => record.caseId).join(",")}\n`);
  process.stdout.write(`OKX FAIL cases ${document.records.filter((record) => record.okx.status === STATUS.FAIL).map((record) => record.caseId).join(",")}\n`);
}
