import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { isDeepStrictEqual } from "node:util";

const CATEGORY_DEFINITIONS = [
  {
    id: "category_1",
    title: "现货基础买卖与盈亏",
    description: "市价买入、加仓、部分卖出、全部卖出以及含手续费的实际盈亏。",
  },
  {
    id: "category_2",
    title: "现货挂单、止损与 OCO",
    description: "LIMIT、STOP_MARKET 和 OCO 的等待、触发、改单、撤单与双腿互斥。",
  },
  {
    id: "category_3",
    title: "现货风控、幂等与并发",
    description: "余额、精度、最小成交额、非法字段、请求重放和并发资金竞争。",
  },
  {
    id: "category_4",
    title: "永续开仓、加仓、减仓与平仓",
    description: "多空开仓、同向加仓、部分/全部平仓、反手和 reduceOnly 数量边界。",
  },
  {
    id: "category_5",
    title: "永续挂单与订单生命周期",
    description: "MARKET、LIMIT、STOP_MARKET 从提交、等待、触发到成交或撤销的状态变化。",
  },
  {
    id: "category_6",
    title: "永续保证金、杠杆与仓位模式",
    description: "CROSS/ISOLATED、ONE_WAY/HEDGE、杠杆变更及逐仓保证金调整。",
  },
  {
    id: "category_7",
    title: "永续资金费",
    description: "正、负、零资金费率，以及结算前仓位变化和资金费引发的风险变化。",
  },
  {
    id: "category_8",
    title: "永续止盈止损、强平与破产保护",
    description: "附加/独立保护单、止盈止损、保护单缩量、强平与穿仓差额。",
  },
  {
    id: "category_9",
    title: "永续风控、幂等、权限与并发",
    description: "行情有效性、事务回滚、批量部分失败、权限、幂等与并发结算唯一性。",
  },
];

const CATEGORY_SHORT_LABELS = {
  category_1: "现货基础",
  category_2: "现货挂单",
  category_3: "现货风控",
  category_4: "永续开平仓",
  category_5: "永续挂单",
  category_6: "保证金/杠杆",
  category_7: "资金费",
  category_8: "保护/强平",
  category_9: "永续风控",
};

const ACTION_LABELS = {
  ADD: "开仓或加仓",
  ADJUST_MARGIN: "调整逐仓保证金",
  ADMIN_FORCE_CLOSE: "管理员强制平仓",
  BUY: "买入",
  CANCEL: "撤销订单",
  CANCEL_ALL: "批量撤销订单",
  CHANGE_LEVERAGE: "调整杠杆",
  CLOSE_ALL: "批量全部平仓",
  CREATE_OCO: "创建 OCO 双腿订单",
  FULL_CLOSE: "全部平仓",
  LIQUIDATE: "执行强平",
  MODIFY: "修改订单",
  PARTIAL_CLOSE: "部分平仓",
  PARTIAL_SELL: "部分卖出",
  PLACE_ORDER: "提交订单",
  RACE: "执行并发竞争",
  REPLAY: "重放幂等请求",
  REVALUE: "按新行情重估",
  REVERSE: "反手开仓",
  SELL: "卖出",
  SET_PROTECTION: "设置止盈止损保护",
  SETTLE_FUNDING: "结算资金费",
  TRIGGER: "触发挂单或保护单",
};

const ORDER_TYPE_LABELS = {
  LIMIT: "限价单",
  MARKET: "市价单",
  STOP: "旧 STOP 兼容输入",
  STOP_MARKET: "止损市价单",
};

const POSITION_MODE_LABELS = {
  HEDGE: "双向持仓",
  ONE_WAY: "单向净持仓",
};

const MARGIN_MODE_LABELS = {
  CASH: "现货现金",
  CROSS: "全仓",
  ISOLATED: "逐仓",
};

const QUANTITY_UNIT_LABELS = {
  BASE: "基础币数量",
  CONTRACTS: "合约张数",
  QUOTE: "计价币金额",
};

const ORDER_STATUS_LABELS = {
  ACCEPTED: "已接受",
  CANCELED: "已撤销",
  FILLED: "已全部成交",
  PENDING: "等待成交",
  PENDING_ACTIVATION: "等待触发",
  REJECTED: "已拒绝",
};

const PROTECTION_TYPE_LABELS = {
  STOP_LOSS: "止损",
  TAKE_PROFIT: "止盈",
};

const PROTECTION_STATUS_LABELS = {
  ACTIVE: "有效",
  CANCELED: "已撤销",
  EXPIRED: "已失效",
  FILLED: "已成交",
  TRIGGERED: "已触发",
};

const CASE_NAME_OVERRIDES = {
  SPOT_SELL_PROFIT: "现货低价买入后高价卖出盈利",
  SPOT_SELL_LOSS: "现货买入后下跌卖出亏损",
  SPOT_SELL_GROSS_BREAKEVEN: "现货毛价保本卖出并计入手续费",
  SPOT_FULL_ZERO_REBUY_RESET: "现货清仓归零后重新买入并重置成本",
  SPOT_MULTI_BUY_SELL: "现货多次买入和多次卖出的成本与盈亏",
  PERP_CLOSE_PROFIT: "永续多仓盈利平仓",
  PERP_CLOSE_LOSS: "永续多仓亏损平仓",
  PERP_CLOSE_GROSS_BREAKEVEN: "永续毛价保本平仓并计入手续费",
  PERP_LONG_TO_SHORT_REVERSAL: "永续多仓反手为空仓",
  PERP_SHORT_TO_LONG_REVERSAL: "永续空仓反手为多仓",
  PERP_HEDGE_INDEPENDENT: "永续双向持仓的多空槽位独立结算",
  PERP_MULTI_POSITION_RECOVERY: "多仓位逐个强平后的风险恢复",
  PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL: "强平后记录穿仓差额",
  PERP_SAME_POSITION_DOUBLE_CLOSE: "同一仓位并发平仓只能结算一次",
};

const evidenceCache = new Map();

function asArray(value) {
  return Array.isArray(value) ? value : value == null ? [] : [value];
}

function parseJson(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    throw new Error(`无法读取 JSON 证据 ${path}: ${error.message}`, { cause: error });
  }
}

function numberOrNull(value) {
  if (value == null || value === "") {
    return null;
  }
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

function format8(value) {
  const numeric = numberOrNull(value);
  return numeric == null ? "未记录" : numeric.toFixed(8);
}

function formatSigned8(value) {
  return formatScaled8(toScaled8(value), true);
}

function toScaled8(value) {
  const numeric = numberOrNull(value) ?? 0;
  const fixed = numeric.toFixed(8);
  const negative = fixed.startsWith("-");
  const unsigned = negative ? fixed.slice(1) : fixed;
  const [whole, fraction = ""] = unsigned.split(".");
  const scaled = BigInt(`${whole}${fraction.padEnd(8, "0").slice(0, 8)}`);
  return negative ? -scaled : scaled;
}

function sumScaled8(values) {
  return values.reduce((sum, value) => sum + toScaled8(value), 0n);
}

function formatScaled8(value, signed = false) {
  const negative = value < 0n;
  const absolute = negative ? -value : value;
  const digits = absolute.toString().padStart(9, "0");
  const formatted = `${digits.slice(0, -8)}.${digits.slice(-8)}`;
  if (negative) {
    return `-${formatted}`;
  }
  return signed && value > 0n ? `+${formatted}` : formatted;
}

function compactActionValues(scenario, selector, fallback) {
  const values = asArray(scenario.actions)
    .map(selector)
    .filter((value) => value != null && value !== "");
  if (values.length === 0 && fallback != null && fallback !== "") {
    values.push(fallback);
  }
  return values.filter((value, index) => index === 0 || value !== values[index - 1]);
}

function actionLabel(action) {
  return ACTION_LABELS[action?.type] ?? action?.type ?? "未知动作";
}

function directionLabel(direction) {
  if (direction === "LONG") {
    return "多仓";
  }
  if (direction === "SHORT") {
    return "空仓";
  }
  return "净持仓";
}

function actionTypes(scenario) {
  return asArray(scenario.actions).map((action) => action.type);
}

export function classifyScenario(scenario) {
  const caseId = String(scenario?.caseId ?? "");
  const types = actionTypes(scenario);

  if (scenario?.productType === "CRYPTO_SPOT") {
    if (/RACE|CLIENT_ORDER|REPLAY/.test(caseId) || types.includes("RACE") || types.includes("REPLAY")) {
      return "category_3";
    }
    if (/LIMIT|STOP|OCO/.test(caseId) || types.some((type) => ["CREATE_OCO", "MODIFY", "CANCEL", "TRIGGER"].includes(type))) {
      return "category_2";
    }
    if (scenario.expectedError || /INSUFFICIENT|OVERSELL|PRECISION|NOTIONAL|LEVERAGE|REDUCE_ONLY|PROTECTION|REJECT/.test(caseId)) {
      return "category_3";
    }
    return "category_1";
  }

  if (scenario?.productType !== "LINEAR_PERP") {
    throw new Error(`不支持的产品类型：${scenario?.productType ?? "<missing>"}`);
  }

  if (/FUNDING/.test(caseId) || types.includes("SETTLE_FUNDING")) {
    return "category_7";
  }

  const explicitRiskCases = new Set([
    "PERP_CANCEL_ALL_PARTIAL_FAILURE",
    "PERP_CLOSE_ALL_PARTIAL_FAILURE",
    "PERP_INCOMPLETE_MARKET",
    "PERP_LEDGER_ROLLBACK",
    "PERP_LOCK_WAIT_EXPIRY",
    "PERP_SAME_POSITION_DOUBLE_CLOSE",
    "PERP_TRADE_ROLLBACK",
  ]);
  if (
    explicitRiskCases.has(caseId) ||
    /RACE|CLIENT_ORDER|REPLAY|ROLLBACK|STALE_MARKET|PROVIDER_SWITCH|PERMISSION|ADMIN_FORCE|DOUBLE_CLOSE/.test(caseId) ||
    types.some((type) => ["RACE", "REPLAY", "ADMIN_FORCE_CLOSE"].includes(type))
  ) {
    return "category_9";
  }

  if (
    caseId === "PERP_MULTI_POSITION_RECOVERY" ||
    /LIQUIDATION|BANKRUPTCY|PROTECTION|ATTACHED_TP|ATTACHED_SL|INDEPENDENT_TP|INDEPENDENT_SL|MULTI_PROTECTION|_TP_|_SL_|_TP$|_SL$/.test(caseId) ||
    types.some((type) => ["LIQUIDATE", "SET_PROTECTION"].includes(type))
  ) {
    return "category_8";
  }

  if (
    /LIMIT|STOP_MARKET|ORDER_MARKET|PARTIAL_FILL/.test(caseId) ||
    types.some((type) => ["CANCEL", "CANCEL_ALL", "MODIFY"].includes(type))
  ) {
    return "category_5";
  }

  if (
    /MARGIN|LEVERAGE|HEDGE|ONE_WAY/.test(caseId) ||
    types.some((type) => ["CHANGE_LEVERAGE", "ADJUST_MARGIN"].includes(type))
  ) {
    return "category_6";
  }

  if (/REDUCE_ONLY/.test(caseId)) {
    return "category_4";
  }

  if (
    scenario.expectedError ||
    /MIN_SIZE|PRECISION|STEP_REJECT|INCOMPLETE|EXECUTION_UNAVAILABLE/.test(caseId)
  ) {
    return "category_9";
  }

  return "category_4";
}

function scenarioName(scenario) {
  const overridden = CASE_NAME_OVERRIDES[scenario.caseId];
  if (overridden) {
    return `${overridden}（${scenario.caseId}）`;
  }
  const product = scenario.productType === "CRYPTO_SPOT" ? "现货" : "永续";
  const chain = asArray(scenario.actions).map(actionLabel).join(" → ") || "状态检查";
  const directions = [
    ...new Set(asArray(scenario.actions).map((action) => action.direction).filter((value) => value && value !== "BOTH")),
  ];
  const direction = directions.length > 0 ? `，${directions.map(directionLabel).join("/")}` : "";
  return `${product}${direction}：${chain}（${scenario.caseId}）`;
}

function describeConditions(scenario, actual) {
  const paths = [...new Set(asArray(scenario.priceSteps).map((step) => step.path).filter(Boolean))];
  const lastPrices = asArray(scenario.priceSteps)
    .map((step) => numberOrNull(step.last))
    .filter((value) => value != null)
    .map(format8);
  const priceDescription = paths.length > 0
    ? `价格路径 ${paths.join(" / ")}（last：${lastPrices.join(" → ")}）`
    : "未配置价格路径";
  const orderTypes = compactActionValues(
    scenario,
    (action) => action.parameters?.orderType,
    scenario.orderType,
  ).map((value) => ORDER_TYPE_LABELS[value] ?? value);
  const quantityUnits = compactActionValues(
    scenario,
    (action) => action.parameters?.quantityUnit,
    scenario.quantityUnit,
  ).map((value) => QUANTITY_UNIT_LABELS[value] ?? value);
  const orderType = `动作订单类型：${orderTypes.join(" → ")}`;
  const quantityUnit = `动作数量单位：${quantityUnits.join(" → ")}`;

  if (scenario.productType === "CRYPTO_SPOT") {
    return `BTCUSDT 现货；${MARGIN_MODE_LABELS[scenario.marginMode] ?? scenario.marginMode}；${orderType}；${quantityUnit}；${priceDescription}`;
  }

  const actionDirections = [
    ...new Set(asArray(scenario.actions).map((action) => action.direction).filter((value) => value && value !== "BOTH")),
  ];
  const direction = actionDirections.length > 0
    ? actionDirections.map(directionLabel).join("/")
    : directionLabel(scenario.positionSide);
  const seenOrderRefs = new Set();
  const reduceOnlyValues = [];
  const pushReduceOnly = (value) => {
    const text = String(Boolean(value));
    if (reduceOnlyValues.at(-1) !== text) {
      reduceOnlyValues.push(text);
    }
  };
  const actions = asArray(scenario.actions);
  for (const [checkpointIndex, checkpoint] of asArray(actual.checkpoints).entries()) {
    const action = actions.find((candidate) => candidate.parameters?.actionId === checkpoint.actionId)
      ?? actions[Math.max(0, Number(checkpoint.actionIndex ?? checkpointIndex + 1) - 1)];
    if (typeof action?.parameters?.reduceOnly === "boolean") {
      pushReduceOnly(action.parameters.reduceOnly);
    }
    for (const [index, order] of asArray(checkpoint.snapshot?.orders).entries()) {
      const orderRef = order.ref ?? `${order.side}:${order.type}:${index}`;
      if (seenOrderRefs.has(orderRef)) {
        continue;
      }
      seenOrderRefs.add(orderRef);
      pushReduceOnly(order.reduceOnly);
    }
  }
  if (reduceOnlyValues.length === 0) {
    pushReduceOnly(scenario.reduceOnly);
  }
  return `BTCUSDT-PERP USDT 线性永续；${POSITION_MODE_LABELS[scenario.positionMode] ?? scenario.positionMode}；${direction}；${MARGIN_MODE_LABELS[scenario.marginMode] ?? scenario.marginMode}；${scenario.leverage}x；${orderType}；${quantityUnit}；动作 reduceOnly：${reduceOnlyValues.join(" → ")}；${priceDescription}`;
}

function tradeKey(trade, index) {
  return trade.ref ?? trade.uniqueKey ?? `${trade.orderRef ?? "trade"}:${index}:${trade.side}:${trade.quantity}:${trade.price}`;
}

function ledgerKey(entry, index) {
  return `${entry.sequence ?? index}:${entry.type ?? ""}:${entry.reference ?? ""}:${entry.amount ?? ""}`;
}

function orderStateKey(order) {
  return [order.status, order.filledQuantity, order.remainingQuantity, order.holdAmount, order.errorCode].join(":");
}

function protectionStateKey(protection) {
  return [protection.status, protection.quantity, protection.triggerPrice, protection.limitPrice].join(":");
}

function isClosingAction(action) {
  return [
    "ADMIN_FORCE_CLOSE",
    "CLOSE_ALL",
    "FULL_CLOSE",
    "LIQUIDATE",
    "PARTIAL_CLOSE",
    "REVERSE",
  ].includes(action?.type) || Boolean(action?.parameters?.reduceOnly);
}

function tradeDirection(action, trade, closing) {
  if (trade.positionSide && trade.positionSide !== "BOTH") {
    return directionLabel(trade.positionSide);
  }
  if (action?.direction && action.direction !== "BOTH") {
    return directionLabel(action.direction);
  }
  if (closing) {
    return trade.side === "SELL" ? "多仓" : "空仓";
  }
  return trade.side === "BUY" ? "多仓" : "空仓";
}

function isClosingTrade(action, order) {
  if (order?.reduceOnly === true || order?.origin === "PROTECTIVE") {
    return true;
  }
  return isClosingAction(action);
}

function describeOneWayNettingTrade(scenario, action, trade, order, previousSnapshot) {
  if (
    scenario.productType !== "LINEAR_PERP" ||
    scenario.positionMode !== "ONE_WAY" ||
    isClosingTrade(action, order)
  ) {
    return null;
  }
  const previousPosition = asArray(previousSnapshot?.positions).find(
    (position) =>
      position.status === "OPEN" &&
      (numberOrNull(position.quantity) ?? 0) > 0 &&
      ["BUY", "SELL"].includes(position.side),
  );
  if (!previousPosition || previousPosition.side === trade.side) {
    return null;
  }

  const previousQuantity = numberOrNull(previousPosition.quantity) ?? 0;
  const tradeQuantity = numberOrNull(trade.quantity) ?? 0;
  const closeQuantity = Math.min(previousQuantity, tradeQuantity);
  const openQuantity = Math.max(0, tradeQuantity - closeQuantity);
  const oldDirection = previousPosition.side === "BUY" ? "多仓" : "空仓";
  const newDirection = trade.side === "BUY" ? "多仓" : "空仓";
  const operation = openQuantity > 0
    ? `单向净持仓反转：先平${oldDirection} ${format8(closeQuantity)} BTC，再开${newDirection} ${format8(openQuantity)} BTC`
    : `单向净持仓抵扣：平${oldDirection} ${format8(closeQuantity)} BTC`;
  const feeAsset = trade.feeAsset ? ` ${trade.feeAsset}` : "（证据未记录手续费资产）";
  const notional = numberOrNull(trade.quoteNotional) == null
    ? ""
    : `，名义价值 ${format8(trade.quoteNotional)} USDT`;
  return `${operation}；合计成交 ${format8(trade.quantity)} BTC，成交价 ${format8(trade.price)} USDT${notional}，手续费 ${format8(trade.fee)}${feeAsset}，该笔交易已实现盈亏 ${format8(trade.realizedPnl)} USDT`;
}

function describeTrade(scenario, action, trade, order, previousSnapshot) {
  const quantity = `${format8(trade.quantity)} BTC`;
  const price = `${format8(trade.price)} USDT`;
  const feeAsset = trade.feeAsset ? ` ${trade.feeAsset}` : "（证据未记录手续费资产）";
  const fee = `${format8(trade.fee)}${feeAsset}`;
  const notional = numberOrNull(trade.quoteNotional) == null
    ? ""
    : `，${scenario.productType === "CRYPTO_SPOT" ? "成交额" : "名义价值"} ${format8(trade.quoteNotional)} USDT`;

  if (scenario.productType === "CRYPTO_SPOT") {
    const verb = trade.side === "BUY" ? "买入" : "卖出";
    return `${verb} ${quantity}，成交价 ${price}${notional}，手续费 ${fee}`;
  }

  const oneWayNetting = describeOneWayNettingTrade(scenario, action, trade, order, previousSnapshot);
  if (oneWayNetting) {
    return oneWayNetting;
  }

  const closing = isClosingTrade(action, order);
  const direction = tradeDirection(action, trade, closing);
  let verb;
  if (action?.type === "LIQUIDATE") {
    verb = `${direction}强平成交`;
  } else if (action?.type === "REVERSE") {
    verb = "反手成交";
  } else if (closing) {
    verb = `${direction}平仓`;
  } else {
    verb = `${direction}开仓/加仓`;
  }
  const realized = numberOrNull(trade.realizedPnl);
  const realizedText = realized != null && (closing || realized !== 0)
    ? `，该笔交易已实现盈亏 ${format8(realized)} USDT`
    : "";
  return `${verb} ${quantity}，成交价 ${price}${notional}，手续费 ${fee}${realizedText}`;
}

function describeOrder(order) {
  const status = ORDER_STATUS_LABELS[order.status] ?? order.status ?? "未记录";
  const pieces = [
    `${order.type ?? "订单"} ${status}`,
    `数量 ${format8(order.quantity)}`,
    `已成交 ${format8(order.filledQuantity)}`,
    `剩余 ${format8(order.remainingQuantity)}`,
  ];
  if (numberOrNull(order.avgFillPrice) != null) {
    pieces.push(`均价 ${format8(order.avgFillPrice)}`);
  }
  if (numberOrNull(order.holdAmount) != null && numberOrNull(order.holdAmount) !== 0) {
    pieces.push(`锁定 ${format8(order.holdAmount)} ${order.holdAsset || "资产"}`);
  }
  return pieces.join("，");
}

function describeProtection(protection) {
  const type = PROTECTION_TYPE_LABELS[protection.type] ?? protection.type ?? "保护单";
  const status = PROTECTION_STATUS_LABELS[protection.status] ?? protection.status ?? "未记录";
  const pieces = [`${type}${status}`, `数量 ${format8(protection.quantity)}`];
  if (numberOrNull(protection.triggerPrice) != null) {
    pieces.push(`触发价 ${format8(protection.triggerPrice)}`);
  }
  if (numberOrNull(protection.limitPrice) != null) {
    pieces.push(`限价 ${format8(protection.limitPrice)}`);
  }
  return pieces.join("，");
}

function formatRequestNumber(value) {
  const numeric = numberOrNull(value);
  return numeric == null ? null : String(numeric);
}

function describeActionRequest(action) {
  const parameters = action?.parameters ?? {};
  const pieces = [];
  if (parameters.side) {
    const sideLabel = parameters.side === "BUY" ? "买入" : parameters.side === "SELL" ? "卖出" : parameters.side;
    pieces.push(`方向 ${parameters.side}（${sideLabel}）`);
  }
  if (parameters.orderType) {
    pieces.push(`订单类型 ${parameters.orderType}（${ORDER_TYPE_LABELS[parameters.orderType] ?? parameters.orderType}）`);
  }
  const quantity = formatRequestNumber(action?.quantity ?? parameters.quantity);
  if (quantity != null) {
    const unit = parameters.quantityUnit;
    if (unit === "QUOTE") {
      pieces.push(`请求金额 ${quantity} USDT（计价币金额）`);
    } else if (unit === "BASE") {
      pieces.push(`请求数量 ${quantity} BTC（基础币数量）`);
    } else if (unit === "CONTRACTS") {
      pieces.push(`请求数量 ${quantity}（合约张数）`);
    } else {
      pieces.push(`请求数量 ${quantity}`);
    }
  }
  const price = formatRequestNumber(parameters.price);
  if (price != null) {
    pieces.push(`委托价 ${price}`);
  }
  const triggerPrice = formatRequestNumber(parameters.triggerPrice);
  if (triggerPrice != null) {
    pieces.push(`触发价 ${triggerPrice}`);
  }
  if (parameters.triggerPriceType) {
    const triggerTypeLabels = {
      INDEX_PRICE: "指数价格",
      LAST_PRICE: "最新成交价",
      MARK_PRICE: "标记价格",
    };
    pieces.push(`触发价类型 ${parameters.triggerPriceType}（${triggerTypeLabels[parameters.triggerPriceType] ?? parameters.triggerPriceType}）`);
  }
  if (typeof parameters.reduceOnly === "boolean") {
    pieces.push(`reduceOnly ${parameters.reduceOnly}`);
  }
  const leverage = formatRequestNumber(parameters.leverage);
  if (leverage != null) {
    pieces.push(`请求杠杆 ${leverage}x`);
  }
  const marginDelta = formatRequestNumber(parameters.marginDelta);
  if (marginDelta != null) {
    pieces.push(`保证金调整 ${marginDelta} USDT`);
  }
  return pieces.length > 0 ? `请求参数：${pieces.join("，")}` : "";
}

function describeFundingEntry(entry) {
  const amount = numberOrNull(entry.amount) ?? 0;
  if (entry.referenceType === "POSITION") {
    return `强平结算重分类 ${formatSigned8(amount)} ${entry.asset ?? ""}（非新增资金费现金流）`.trim();
  }
  const flow = amount > 0 ? "资金费入账" : amount < 0 ? "资金费扣款" : "资金费为零";
  const settlement = entry.referenceType === "FUNDING_SETTLEMENT" ? "（FUNDING_SETTLEMENT 现金流）" : "";
  return `${flow} ${formatSigned8(amount)} ${entry.asset ?? ""}${settlement}`.trim();
}

function describeNonTradeStep(action, checkpoint, newLedgerEntries, changedOrders, changedProtections) {
  const failure = checkpoint.failure;
  if (failure) {
    const message = failure.message ? `（${failure.message}）` : "";
    const request = describeActionRequest(action);
    return `${request ? `${request}；` : ""}操作被拒绝：${failure.code}${message}；该检查点新增成交 0 笔`;
  }

  const fundingEntries = newLedgerEntries.filter((entry) => entry.type === "FUNDING_FEE");
  if (fundingEntries.length > 0) {
    return fundingEntries.map(describeFundingEntry).join("；");
  }

  if (action?.type === "REPLAY") {
    return "幂等重放返回原订单结果；未新增成交、手续费或账本记录";
  }

  if (action?.type === "SETTLE_FUNDING") {
    const fundingRate = numberOrNull(action.parameters?.fundingRate);
    const hasOpenPosition = asArray(checkpoint.snapshot?.positions).some(
      (position) => position.status === "OPEN" && (numberOrNull(position.quantity) ?? 0) > 0,
    );
    if (fundingRate === 0) {
      return "资金费率为 0；本次结算现金流为 0 USDT";
    }
    if (!hasOpenPosition) {
      return "结算时已无未平仓仓位；本次资金费为 0 USDT";
    }
    return "完成资金费检查；实际账本没有新增资金费现金流";
  }

  if (action?.type === "LIQUIDATE") {
    return "账户权益仍满足维护保证金要求，未达到强平条件；未产生强平成交";
  }

  if (changedProtections.length > 0) {
    return changedProtections.map(describeProtection).join("；");
  }

  if (action?.type === "REVALUE") {
    const positions = asArray(checkpoint.snapshot?.positions);
    return positions.length > 0
      ? positions.map((position) => `${position.slot} 标记价重估为 ${format8(position.markPrice)}，未实现盈亏 ${format8(position.unrealizedPnl)} USDT`).join("；")
      : "按实际行情完成重估；当前没有未平仓仓位";
  }

  if (action?.type === "CHANGE_LEVERAGE") {
    const positions = asArray(checkpoint.snapshot?.positions);
    return positions.length > 0
      ? positions.map((position) => `${position.slot} 杠杆调整为 ${position.leverage}x，保证金占用 ${format8(position.marginHeld)} USDT`).join("；")
      : "杠杆调整完成；当前没有未平仓仓位";
  }

  if (action?.type === "ADJUST_MARGIN") {
    const positions = asArray(checkpoint.snapshot?.positions);
    return positions.length > 0
      ? positions.map((position) => `${position.slot} 逐仓保证金变为 ${format8(position.marginHeld)} USDT`).join("；")
      : "逐仓保证金调整完成；当前没有未平仓仓位";
  }

  if (changedOrders.length > 0) {
    const request = describeActionRequest(action);
    return `${request ? `${request}；` : ""}${changedOrders.map(describeOrder).join("；")}`;
  }

  const snapshot = checkpoint.snapshot ?? {};
  return `该步无新增成交；实际快照包含订单 ${asArray(snapshot.orders).length} 笔、仓位 ${asArray(snapshot.positions).length} 个、账本 ${asArray(snapshot.ledger).length} 条`;
}

function describeTimeline(scenario, actual) {
  const seenTrades = new Set();
  const seenLedgerEntries = new Set();
  const orderStates = new Map();
  const protectionStates = new Map();
  const steps = [];
  let previousSnapshot = null;

  for (const [checkpointIndex, checkpoint] of asArray(actual.checkpoints).entries()) {
    const action = asArray(scenario.actions).find((candidate) => candidate.parameters?.actionId === checkpoint.actionId)
      ?? asArray(scenario.actions)[Math.max(0, Number(checkpoint.actionIndex ?? checkpointIndex + 1) - 1)];
    const snapshot = checkpoint.snapshot ?? {};
    const newTrades = asArray(snapshot.trades).filter((trade, index) => {
      const key = tradeKey(trade, index);
      if (seenTrades.has(key)) {
        return false;
      }
      seenTrades.add(key);
      return true;
    });
    const newLedgerEntries = asArray(snapshot.ledger).filter((entry, index) => {
      const key = ledgerKey(entry, index);
      if (seenLedgerEntries.has(key)) {
        return false;
      }
      seenLedgerEntries.add(key);
      return true;
    });
    const changedOrders = asArray(snapshot.orders).filter((order) => {
      const key = order.ref ?? `${order.side}:${order.type}`;
      const state = orderStateKey(order);
      const changed = orderStates.get(key) !== state;
      orderStates.set(key, state);
      return changed;
    });
    const changedProtections = asArray(snapshot.protections).filter((protection) => {
      const key = protection.ref ?? `${protection.type}:${protection.positionRef}`;
      const state = protectionStateKey(protection);
      const changed = protectionStates.get(key) !== state;
      protectionStates.set(key, state);
      return changed;
    });

    const ordersByRef = new Map(asArray(snapshot.orders).map((order) => [order.ref, order]));
    const descriptions = newTrades.map((trade) =>
      describeTrade(scenario, action, trade, ordersByRef.get(trade.orderRef), previousSnapshot)
    );
    if (descriptions.length === 0) {
      descriptions.push(describeNonTradeStep(action, checkpoint, newLedgerEntries, changedOrders, changedProtections));
    } else {
      const fundingEntries = newLedgerEntries.filter((entry) => entry.type === "FUNDING_FEE");
      for (const entry of fundingEntries) {
        descriptions.push(describeFundingEntry(entry));
      }
      if (changedProtections.length > 0) {
        descriptions.push(changedProtections.map(describeProtection).join("；"));
      }
      const tradedOrderRefs = new Set(newTrades.map((trade) => trade.orderRef).filter(Boolean));
      const canceledSiblingOrders = changedOrders.filter(
        (order) => order.status === "CANCELED" && !tradedOrderRefs.has(order.ref),
      );
      if (canceledSiblingOrders.length > 0) {
        descriptions.push(`关联订单：${canceledSiblingOrders.map(describeOrder).join("；")}`);
      }
      if (checkpoint.failure) {
        descriptions.push(`操作被拒绝：${checkpoint.failure.code}`);
      }
    }

    const stepNumber = Number(checkpoint.actionIndex ?? checkpointIndex + 1);
    steps.push(`第${stepNumber}步（${actionLabel(action)}）：${descriptions.join("；")}`);
    previousSnapshot = snapshot;
  }

  return steps.join("。 ") || "没有执行检查点，无法生成交易过程";
}

function finalSnapshot(actual) {
  const checkpoints = asArray(actual.checkpoints);
  if (checkpoints.length === 0 || !checkpoints.at(-1)?.snapshot) {
    throw new Error(`${actual.caseId} 缺少最终实际快照`);
  }
  return checkpoints.at(-1).snapshot;
}

function summarizeFees(snapshot) {
  const fees = new Map();
  for (const entry of asArray(snapshot.ledger).filter((candidate) => candidate.type === "TRADE_FEE")) {
    const asset = entry.asset || "未记录资产";
    const amount = toScaled8(entry.amount);
    fees.set(asset, (fees.get(asset) ?? 0n) + (amount < 0n ? -amount : amount));
  }
  return [...fees.entries()].map(([asset, amount]) => `${formatScaled8(amount)} ${asset}`).join("，");
}

function summarizeActualFinancialImpact(scenario, snapshot) {
  const positions = asArray(snapshot.positions);
  const positionRealized = sumScaled8(positions.map((position) => position.realizedPnl));
  const unrealized = sumScaled8(positions.map((position) => position.unrealizedPnl));
  const feeSummary = summarizeFees(snapshot);

  if (scenario.productType === "CRYPTO_SPOT") {
    const wallets = asArray(snapshot.wallets);
    const walletTotals = wallets
      .map((wallet) => `${wallet.asset ?? "未记录资产"} ${format8(wallet.total)}`)
      .join("，");
    const walletDeltas = wallets
      .map((wallet) => {
        const initial = numberOrNull(scenario.initialBalances?.[wallet.asset]);
        const final = numberOrNull(wallet.total);
        const delta = initial == null || final == null ? null : toScaled8(final) - toScaled8(initial);
        return `${wallet.asset ?? "未记录资产"} ${delta == null ? "未记录" : formatScaled8(delta, true)}`;
      })
      .join("，");
    return `已实现盈亏 ${formatScaled8(positionRealized)} USDT；未实现盈亏 ${formatScaled8(unrealized)} USDT；${feeSummary ? `实际交易手续费 ${feeSummary}` : "无实际交易手续费扣费记录"}；最终钱包总额 ${walletTotals || "未记录"}；相对初始变化 ${walletDeltas || "未记录"}；账户汇总快照余额 ${format8(snapshot.account?.balance)} USDT、权益 ${format8(snapshot.account?.equity)} USDT。`;
  }

  const realized = sumScaled8(asArray(snapshot.trades).map((trade) => trade.realizedPnl));
  const funding = sumScaled8(
    asArray(snapshot.ledger)
      .filter(
        (entry) => entry.type === "FUNDING_FEE" && entry.referenceType === "FUNDING_SETTLEMENT",
      )
      .map((entry) => entry.amount),
  );
  const initialUsdt = scenario.initialBalances?.USDT;
  const finalBalance = snapshot.account?.balance;
  const netChange = numberOrNull(initialUsdt) == null || numberOrNull(finalBalance) == null
    ? null
    : toScaled8(finalBalance) - toScaled8(initialUsdt);
  const netText = netChange == null
    ? "账户余额变动证据未提供"
    : `账户余额变动（最终余额 - 初始 USDT）${formatScaled8(netChange, true)} USDT`;
  const shortfall = toScaled8(snapshot.account?.bankruptcyShortfall);
  const shortfallText = shortfall === 0n ? "" : `；穿仓差额 ${formatScaled8(shortfall)} USDT`;
  return `交易已实现盈亏 ${formatScaled8(realized)} USDT；未实现盈亏 ${formatScaled8(unrealized)} USDT；资金费结算现金流 ${formatScaled8(funding, true)} USDT；${feeSummary ? `实际交易手续费 ${feeSummary}` : "无实际交易手续费扣费记录"}；最终余额 ${format8(finalBalance)} USDT；最终权益 ${format8(snapshot.account?.equity)} USDT；占用保证金 ${format8(snapshot.account?.usedMargin)} USDT；${netText}${shortfallText}。`;
}

function summarizePnl(scenario, actual) {
  const snapshot = finalSnapshot(actual);
  const financialImpact = summarizeActualFinancialImpact(scenario, snapshot);
  const failures = asArray(actual.checkpoints).map((checkpoint) => checkpoint.failure).filter(Boolean);
  if (scenario.expectedError) {
    const failure = failures.find((candidate) => candidate.code === scenario.expectedError);
    const checkpoints = asArray(actual.checkpoints);
    const failureIndex = checkpoints.findIndex((checkpoint) => checkpoint.failure?.code === scenario.expectedError);
    const failureTrades = asArray(checkpoints[failureIndex]?.snapshot?.trades);
    const previousTradeRefs = new Set(
      asArray(checkpoints[failureIndex - 1]?.snapshot?.trades).map((trade, index) => tradeKey(trade, index)),
    );
    const failureStepNewTrades = failureTrades.filter(
      (trade, index) => !previousTradeRefs.has(tradeKey(trade, index)),
    ).length;
    const totalTrades = asArray(snapshot.trades).length;
    const hasRace = actionTypes(scenario).includes("RACE");
    const mutationText = hasRace && failureStepNewTrades > 0
      ? `并发步骤新增 ${failureStepNewTrades} 笔合法赢家成交；拒绝分支未产生额外成交`
      : totalTrades > 0
        ? `拒绝前已有 ${totalTrades} 笔合法成交；被拒绝动作未产生额外成交`
        : "被拒绝动作未产生成交";
    return `按预期拒绝：${scenario.expectedError}；实际失败码 ${failure?.code ?? "未记录"}；${mutationText}。实际资金影响（仅来自场景预置状态、已成功前置步骤或并发合法赢家）：${financialImpact} 未伪造被拒动作盈亏。`;
  }
  return financialImpact;
}

function describeAlgorithm(scenario) {
  const caseId = scenario.caseId;
  const types = actionTypes(scenario);

  if (scenario.expectedError) {
    if (scenario.expectedError === "PARTIAL_FILL_NOT_SUPPORTED") {
      return "单次全量成交范围内，PARTIALLY_FILLED 兼容输入必须在资金和仓位提交前被拒绝。";
    }
    if (["MARKET_DATA_STALE", "MARKET_BUNDLE_INCOMPLETE"].includes(scenario.expectedError)) {
      return "行情过期或字段不完整时，风控必须拒绝交易，且不得提交订单、资金或仓位变更。";
    }
    if (scenario.expectedError === "DUPLICATE_CLIENT_ORDER_ID") {
      return "同一 clientOrderId 的相同请求只能幂等返回；参数冲突必须拒绝且不得产生第二笔成交或手续费。";
    }
    if (types.includes("RACE")) {
      return `并发竞争只允许一个合法赢家提交；失败分支返回 ${scenario.expectedError}，且不得追加第二笔成交、手续费或账本。`;
    }
    if (/ROLLBACK/.test(caseId)) {
      return `注入执行失败并返回 ${scenario.expectedError} 时，事务整体回滚；订单、成交、资金和账本不得留下半完成状态。`;
    }
    return `前置校验必须返回 ${scenario.expectedError}，被拒绝的动作不得产生额外成交、手续费或非法资金/仓位变化。`;
  }

  if (/FUNDING.*LIQUIDATION/.test(caseId)) {
    const marginScope = scenario.marginMode === "ISOLATED" ? "逐仓仓位独立承担" : "全仓风险池共同承担";
    return `资金费 = 结算时仓位价值 × 资金费率；资金费扣款后重新计算权益与维护保证金，跌破边界才触发强平；${marginScope}，强平费用与穿仓差额只能按实际账本记录一次。`;
  }
  if (/FUNDING/.test(caseId) || types.includes("SETTLE_FUNDING")) {
    return "资金费 = 结算时仓位价值 × 资金费率；正费率由多头支付空头，负费率方向相反，零费率不转移资金。";
  }
  if (caseId === "PERP_PROVIDER_SWITCH_WITH_GAP") {
    return "行情 provider 从 binance → okx 切换时，必须先完成 symbol binding，并按新 provider 的时间戳和完整字段校验新鲜度；只要新行情有效，价格 gap 本身不得被误判为 stale。";
  }
  if (/RACE|REPLAY|CLIENT_ORDER|ROLLBACK|DOUBLE_CLOSE|PARTIAL_FAILURE/.test(caseId) || types.some((type) => ["RACE", "REPLAY"].includes(type))) {
    return "并发、重放或事务失败时，同一经济动作最多结算一次；合法赢家保留，失败分支不得重复成交、收费或写账。";
  }
  if (/LIQUIDATION|BANKRUPTCY/.test(caseId) || types.includes("LIQUIDATE")) {
    return "当权益达到维护保证金边界才进入强平；强平按实际成交结算盈亏和费用，余额不足的差额只记录一次。";
  }
  if (/PROTECTION|_TP|_SL|ATTACHED|INDEPENDENT/.test(caseId) || types.includes("SET_PROTECTION")) {
    return "止盈止损按触发价类型和价格穿越规则激活；仓位缩减后保护数量同步缩小，平仓后剩余保护单失效。";
  }
  if (caseId === "PERP_ONE_WAY_NETTING") {
    return "单向净持仓模式下，反方向委托先抵扣原方向仓位；超过原仓位的剩余数量才反向开仓，已实现盈亏只按被抵扣数量和原开仓均价计算。";
  }
  if (scenario.productType === "CRYPTO_SPOT") {
    if (types.some((type) => ["SELL", "PARTIAL_SELL"].includes(type))) {
      return "现货已实现盈亏 = 卖出净收入 - 对应持仓的含费成本；买入 base 手续费进入持仓成本，卖出 quote 手续费从收入扣除。";
    }
    if (/LIMIT|STOP|OCO/.test(caseId) || types.some((type) => ["CREATE_OCO", "PLACE_ORDER", "TRIGGER"].includes(type))) {
      return "挂单按价格条件在 PENDING、FILLED、CANCELED 间迁移；OCO 两腿共享一次资金占用，一腿成交后另一腿必须撤销。";
    }
    return "现货净收币 = 毛买入量 - base 手续费；钱包必须满足 total = available + locked 且不得出现负余额。";
  }
  if (/MARGIN|LEVERAGE|HEDGE|ONE_WAY/.test(caseId) || types.some((type) => ["CHANGE_LEVERAGE", "ADJUST_MARGIN"].includes(type))) {
    return "初始保证金按名义价值和杠杆计算；全仓共享风险池、逐仓独立隔离，杠杆或逐仓保证金变更后账户权益关系必须守恒。";
  }
  if (/LIMIT|STOP_MARKET|ORDER_MARKET/.test(caseId) || types.some((type) => ["CANCEL", "MODIFY"].includes(type))) {
    return "订单只能沿合法生命周期迁移；未触价保持等待，触价后按订单类型成交，撤单释放尚未消费的保证金占用。";
  }
  if (types.some((type) => ["PARTIAL_CLOSE", "FULL_CLOSE", "CLOSE_ALL", "REVERSE"].includes(type)) || /CLOSE|REDUCE_ONLY|REVERSAL/.test(caseId)) {
    return "多仓已实现盈亏 =（平仓价 - 开仓均价）× 平仓数量，空仓方向相反；reduceOnly 只能减少现有仓位，不能反向扩仓。";
  }
  return "同向加仓后的开仓均价按成交数量加权；仓位、保证金、手续费、账本与账户汇总必须逐笔一致。";
}

const EVIDENCE_FIELD_LABELS = {
  actionId: "actionId",
  actionIndex: "动作序号",
  amount: "金额",
  asOf: "行情时间",
  asset: "资产",
  available: "可用",
  averageEntry: "开仓均价",
  avgFillPrice: "平均成交价",
  balance: "余额",
  balanceAfter: "变动后余额",
  bankruptcyShortfall: "穿仓差额",
  code: "代码",
  contingencyRef: "联动ID",
  errorCode: "错误码",
  exceptionType: "异常类型",
  expiresAt: "过期时间",
  fee: "手续费",
  feeAsset: "手续费资产",
  filledQuantity: "已成交数量",
  freeMargin: "可用保证金",
  fromStatus: "原状态",
  fundingPnl: "资金费累计",
  holdAmount: "锁定金额",
  holdAsset: "锁定资产",
  holdOwnerRef: "锁定归属",
  initialMargin: "初始保证金",
  leverage: "杠杆",
  limitPrice: "限价",
  liquidityRole: "流动性角色",
  locked: "锁定",
  maintenanceMargin: "维持保证金",
  marginHeld: "保证金占用",
  marginMode: "保证金模式",
  markPrice: "标记价",
  notional: "名义价值",
  operationType: "操作类型",
  orderRef: "订单ID",
  origin: "来源",
  parentRef: "父记录",
  positionMode: "持仓模式",
  positionRef: "仓位ID",
  positionSide: "持仓方向",
  price: "成交价",
  productType: "产品",
  quantity: "数量",
  quoteNotional: "成交额/名义价值",
  realizedPnl: "已实现盈亏",
  reduceOnly: "reduceOnly",
  ref: "记录ID",
  reference: "引用",
  referenceType: "引用类型",
  remainingQuantity: "剩余数量",
  sequence: "序号",
  side: "方向",
  slot: "仓位槽",
  status: "状态",
  subjectRef: "主题ID",
  toStatus: "新状态",
  total: "总额",
  triggerPrice: "触发价",
  type: "类型",
  uniqueKey: "唯一键",
  unrealizedPnl: "未实现盈亏",
  usedMargin: "已用保证金",
  walletType: "钱包类型",
  zeroMutation: "零变更",
};

const SNAPSHOT_COMPONENTS = [
  ["orders", "订单", "订单"],
  ["trades", "成交", "成交"],
  ["positions", "仓位", "仓位"],
  ["wallets", "钱包", "钱包"],
  ["ledger", "账本", "账本记录"],
  ["protections", "保护单", "保护单"],
  ["events", "事件", "事件"],
];

const CHECKPOINT_COMPONENT_LABELS = {
  actionId: "actionId",
  actionIndex: "动作序号",
  account: "账户",
  events: "事件",
  failure: "失败",
  ledger: "账本",
  orders: "订单",
  positions: "仓位",
  protections: "保护单",
  trades: "成交",
  wallets: "钱包",
};

function formatEvidenceValue(value, key = "") {
  if (value == null) {
    return "null";
  }
  if (typeof value === "number") {
    if (["actionIndex", "leverage", "sequence"].includes(key)) {
      return String(value);
    }
    return value.toFixed(8);
  }
  if (typeof value === "boolean") {
    return String(value);
  }
  if (typeof value === "string") {
    return value === "" ? '""' : value;
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => formatEvidenceValue(item)).join("，")}]`;
  }
  return `{${Object.entries(value)
    .map(([childKey, childValue]) => `${EVIDENCE_FIELD_LABELS[childKey] ?? childKey}=${formatEvidenceValue(childValue, childKey)}`)
    .join("，")}}`;
}

function formatEvidenceObject(value) {
  return Object.entries(value ?? {})
    .map(([key, fieldValue]) => `${EVIDENCE_FIELD_LABELS[key] ?? key}=${formatEvidenceValue(fieldValue, key)}`)
    .join("，");
}

function formatEvidenceCollection(component, values) {
  const definition = SNAPSHOT_COMPONENTS.find(([key]) => key === component);
  const label = definition?.[1] ?? component;
  const noun = definition?.[2] ?? "记录";
  const items = asArray(values);
  if (items.length === 0) {
    return `${label}（0）：无`;
  }
  return `${label}（${items.length}）：${items
    .map((item, index) => `${noun}${index + 1}{${formatEvidenceObject(item)}}`)
    .join("；")}`;
}

function formatFailure(failure) {
  return failure ? `失败：{${formatEvidenceObject(failure)}}` : "失败：无";
}

function formatCompleteSnapshot(snapshot, failure) {
  const safeSnapshot = snapshot ?? {};
  return [
    formatEvidenceCollection("orders", safeSnapshot.orders),
    formatEvidenceCollection("trades", safeSnapshot.trades),
    formatEvidenceCollection("positions", safeSnapshot.positions),
    formatEvidenceCollection("wallets", safeSnapshot.wallets),
    `账户：{${formatEvidenceObject(safeSnapshot.account ?? {})}}`,
    formatEvidenceCollection("ledger", safeSnapshot.ledger),
    formatEvidenceCollection("protections", safeSnapshot.protections),
    formatEvidenceCollection("events", safeSnapshot.events),
    formatFailure(failure),
  ].join("\n");
}

function formatMarketStep(step) {
  if (!step) {
    return "该步骤没有独立行情快照";
  }
  const missingFields = asArray(step.missingFields).join(",") || "无";
  return `行情序号 ${step.sequence}（path=${step.path}，source=${step.source}，bid=${format8(step.bid)}，ask=${format8(step.ask)}，last=${format8(step.last)}，mark=${format8(step.mark)}，index=${format8(step.index)}，asOf=${formatEvidenceValue(step.asOf, "asOf")}，expiresAt=${formatEvidenceValue(step.expiresAt, "expiresAt")}，missingFields=${missingFields}）`;
}

function formatInitialBalances(initialBalances) {
  return Object.entries(initialBalances ?? {})
    .map(([asset, value]) => `${asset}=${format8(value)}`)
    .join("，") || "无";
}

function formatRequestParameterValue(value) {
  if (value == null) {
    return "null";
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  if (typeof value === "string") {
    return value === "" ? '""' : value;
  }
  if (Array.isArray(value)) {
    return `[${value.map(formatRequestParameterValue).join("，")}]`;
  }
  return `{${Object.entries(value)
    .map(([key, childValue]) => `${key}=${formatRequestParameterValue(childValue)}`)
    .join("，")}}`;
}

function formatRequestParameters(parameters) {
  return Object.entries(parameters ?? {})
    .map(([key, value]) => `${key === "price" ? "委托价" : key}=${formatRequestParameterValue(value)}`)
    .join("，");
}

function formatCompleteAction(action) {
  if (!action) {
    return "动作未记录";
  }
  const quantity = action.quantity == null ? "null" : String(action.quantity);
  return [
    `动作类型=${action.type}`,
    `方向=${action.direction ?? "null"}`,
    `请求数量=${quantity}`,
    `detail=${action.detail || '""'}`,
    `完整请求参数{${formatRequestParameters(action.parameters ?? {})}}`,
  ].join("，");
}

function componentKey(component, item, index) {
  if (component === "orders" || component === "trades" || component === "protections") {
    return item.ref ?? `${component}:${index}`;
  }
  if (component === "positions") {
    return item.slot ?? `${component}:${index}`;
  }
  if (component === "wallets") {
    return `${item.walletType ?? ""}:${item.asset ?? index}`;
  }
  if (component === "ledger" || component === "events") {
    return item.sequence ?? `${component}:${index}`;
  }
  return `${component}:${index}`;
}

function changedCollection(component, previousValues, currentValues) {
  const previous = asArray(previousValues);
  const current = asArray(currentValues);
  const previousByKey = new Map(previous.map((item, index) => [componentKey(component, item, index), item]));
  const currentKeys = new Set(current.map((item, index) => componentKey(component, item, index)));
  const added = [];
  const updated = [];
  current.forEach((item, index) => {
    const key = componentKey(component, item, index);
    if (!previousByKey.has(key)) {
      added.push(item);
      return;
    }
    const before = previousByKey.get(key);
    if (!isDeepStrictEqual(before, item)) {
      const fieldChanges = {};
      for (const field of new Set([...Object.keys(before), ...Object.keys(item)])) {
        if (!isDeepStrictEqual(before[field], item[field])) {
          fieldChanges[field] = Object.hasOwn(item, field) ? item[field] : null;
        }
      }
      updated.push({ key, fieldChanges });
    }
  });
  const removed = previous
    .map((item, index) => componentKey(component, item, index))
    .filter((key) => !currentKeys.has(key));
  return { added, updated, removed };
}

function formatCollectionChanges(component, changes) {
  const definition = SNAPSHOT_COMPONENTS.find(([key]) => key === component);
  const label = definition?.[1] ?? component;
  const noun = definition?.[2] ?? "记录";
  const pieces = [];
  if (changes.added.length > 0) {
    pieces.push(`新增${formatEvidenceCollection(component, changes.added)}`);
  }
  if (changes.updated.length > 0) {
    pieces.push(`更新${changes.updated.map(({ key, fieldChanges }) =>
      `${noun}{键=${formatEvidenceValue(key)}，变化字段{${formatEvidenceObject(fieldChanges)}}}`
    ).join("；")}`);
  }
  if (changes.removed.length > 0) {
    pieces.push(`移除${label}键=[${changes.removed.join("，")}]`);
  }
  return pieces.join("；");
}

function formatSnapshotChanges(previousSnapshot, checkpoint) {
  const previous = previousSnapshot ?? {};
  const current = checkpoint?.snapshot ?? {};
  const pieces = [];
  for (const [component, label] of SNAPSHOT_COMPONENTS) {
    const changes = changedCollection(component, previous[component], current[component]);
    if (changes.added.length === 0 && changes.updated.length === 0 && changes.removed.length === 0) {
      continue;
    }
    pieces.push(`${label}变化：${formatCollectionChanges(component, changes)}`);
  }
  if (!isDeepStrictEqual(previous.account ?? {}, current.account ?? {})) {
    const accountChanges = Object.fromEntries(
      [...new Set([...Object.keys(previous.account ?? {}), ...Object.keys(current.account ?? {})])]
        .filter((field) => !isDeepStrictEqual(previous.account?.[field], current.account?.[field]))
        .map((field) => [field, Object.hasOwn(current.account ?? {}, field) ? current.account[field] : null]),
    );
    pieces.push(`账户变化：{${formatEvidenceObject(accountChanges)}}`);
  }
  pieces.push(formatFailure(checkpoint?.failure));
  return `${pieces.join("；")}；未列组件=无变化`;
}

function compareCheckpoint(actualCheckpoint, expectedCheckpoint) {
  const actualSnapshot = actualCheckpoint?.snapshot ?? {};
  const expectedSnapshot = expectedCheckpoint?.snapshot ?? {};
  return {
    actionIndex: isDeepStrictEqual(actualCheckpoint?.actionIndex, expectedCheckpoint?.actionIndex),
    actionId: isDeepStrictEqual(actualCheckpoint?.actionId, expectedCheckpoint?.actionId),
    orders: isDeepStrictEqual(asArray(actualSnapshot.orders), asArray(expectedSnapshot.orders)),
    trades: isDeepStrictEqual(asArray(actualSnapshot.trades), asArray(expectedSnapshot.trades)),
    positions: isDeepStrictEqual(asArray(actualSnapshot.positions), asArray(expectedSnapshot.positions)),
    wallets: isDeepStrictEqual(asArray(actualSnapshot.wallets), asArray(expectedSnapshot.wallets)),
    account: isDeepStrictEqual(actualSnapshot.account ?? {}, expectedSnapshot.account ?? {}),
    ledger: isDeepStrictEqual(asArray(actualSnapshot.ledger), asArray(expectedSnapshot.ledger)),
    protections: isDeepStrictEqual(asArray(actualSnapshot.protections), asArray(expectedSnapshot.protections)),
    events: isDeepStrictEqual(asArray(actualSnapshot.events), asArray(expectedSnapshot.events)),
    failure: isDeepStrictEqual(actualCheckpoint?.failure ?? null, expectedCheckpoint?.failure ?? null),
  };
}

function formatCheckpointComparison(comparison) {
  return Object.entries(comparison)
    .map(([component, equal]) => `${CHECKPOINT_COMPONENT_LABELS[component] ?? component}=${equal ? "相等" : "不相等"}`)
    .join("、");
}

function buildComparison(actual, expected, expectedOptionCount = 1, selectedExpectedIndex = 0) {
  const checkpointCountEqual = asArray(actual.checkpoints).length === asArray(expected.checkpoints).length;
  const checkpoints = asArray(actual.checkpoints).map((checkpoint, index) => ({
    stepNumber: Number(checkpoint.actionIndex ?? index + 1),
    ...compareCheckpoint(checkpoint, asArray(expected.checkpoints)[index]),
  }));
  const comparisonPassed =
    actual.caseId === expected.caseId &&
    checkpointCountEqual &&
    isDeepStrictEqual(actual.checkpoints, expected.checkpoints);
  const stepText = checkpoints
    .map(({ stepNumber, ...comparison }) => {
      const equalCount = Object.values(comparison).filter(Boolean).length;
      return equalCount === Object.keys(comparison).length
        ? `第 ${stepNumber} 步：${equalCount}/${Object.keys(comparison).length} 项相等`
        : `第 ${stepNumber} 步：${formatCheckpointComparison(comparison)}`;
    })
    .join("。 ");
  const comparisonScope = `逐项范围：${Object.keys(checkpoints[0] ?? {})
    .filter((component) => component !== "stepNumber")
    .map((component) => CHECKPOINT_COMPONENT_LABELS[component] ?? component)
    .join("、")}（每步均实际深比较）`;
  const totalText = comparisonPassed
    ? `总比对：actual 与 expected 完全相等；两个结果相等，测试成功${expectedOptionCount > 1 ? `；本场景有 ${expectedOptionCount} 个合法 expected 分支，actual 命中第 ${selectedExpectedIndex + 1} 个` : ""}`
    : `总比对：actual 与 expected 不相等；caseId=${actual.caseId === expected.caseId ? "相等" : "不相等"}、checkpoint数量=${checkpointCountEqual ? "相等" : "不相等"}`;
  return {
    checkpoints,
    comparisonPassed,
    comparisonResult: `${stepText}。 ${comparisonScope}。 ${totalText}。`,
  };
}

function scenarioAndInitialData(scenario) {
  const product = scenario.productType === "CRYPTO_SPOT" ? "BTCUSDT 现货" : "BTCUSDT-PERP USDT 线性永续";
  const actionSequences = new Set(asArray(scenario.actions).map((_, index) => index + 1));
  const initialAndUnusedPricePath = asArray(scenario.priceSteps)
    .filter((step) => !actionSequences.has(Number(step.sequence)))
    .map(formatMarketStep)
    .join("。 ");
  return [
    `${scenarioName(scenario)}；产品=${product}；路径结果=${scenario.expectedError ? `预期拒绝 ${scenario.expectedError}` : "预期成功"}`,
    `初始余额：${formatInitialBalances(scenario.initialBalances)}`,
    `初始仓位=${formatEvidenceValue(scenario.initialPosition)}；初始订单=${formatEvidenceValue(scenario.initialOrders)}`,
    `持仓模式=${POSITION_MODE_LABELS[scenario.positionMode] ?? scenario.positionMode}；持仓方向=${scenario.positionSide}；保证金模式=${MARGIN_MODE_LABELS[scenario.marginMode] ?? scenario.marginMode}；杠杆=${scenario.leverage}x；订单类型=${scenario.orderType}；数量单位=${scenario.quantityUnit}；scenario.reduceOnly=${scenario.reduceOnly}`,
    `完整行情路径分栏展示：动作对应行情逐步列在“详细操作步骤”；这里列初始行情及未被动作消费的备用行情：${initialAndUnusedPricePath || "无"}`,
  ].join("\n");
}

function detailedSteps(scenario, actual, expected) {
  const priceSteps = asArray(scenario.priceSteps);
  const actions = asArray(scenario.actions);
  const traces = asArray(expected.calculationTrace);
  const pieces = [`大白话操作总览：${describeTimeline(scenario, actual)}`];
  let previousSnapshot = null;
  for (const [index, checkpoint] of asArray(actual.checkpoints).entries()) {
    const stepNumber = Number(checkpoint.actionIndex ?? index + 1);
    const action = actions.find((candidate) => candidate.parameters?.actionId === checkpoint.actionId)
      ?? actions[Math.max(0, stepNumber - 1)];
    const market = priceSteps.find((step) => Number(step.sequence) === stepNumber)
      ?? priceSteps[Math.min(stepNumber, Math.max(0, priceSteps.length - 1))];
    const expectedCheckpoint = asArray(expected.checkpoints)[index];
    const comparison = compareCheckpoint(checkpoint, expectedCheckpoint);
    pieces.push([
      `步骤 ${stepNumber}（${actionLabel(action)}）`,
      formatMarketStep(market),
      `完整请求参数：${formatCompleteAction(action)}`,
      `实际检查点变化：${formatSnapshotChanges(previousSnapshot, checkpoint)}`,
      `算法本步结果：见右侧“算法计算轨迹”第 ${index + 1} 条${traces[index] == null ? "（expected 未记录独立 calculationTrace）" : "（按 actionId 对应本步骤）"}`,
      `本步 actual/expected 比对：${Object.values(comparison).filter(Boolean).length}/${Object.keys(comparison).length} 项相等；逐项名称与结论见最后一列`,
    ].join("。 "));
    previousSnapshot = checkpoint.snapshot ?? {};
  }
  return pieces.join("\n\n");
}

function actualFinalData(scenario, actual) {
  const checkpoint = asArray(actual.checkpoints).at(-1);
  return [
    "最终 actual 检查点完整数据：",
    formatCompleteSnapshot(checkpoint?.snapshot, checkpoint?.failure),
    `实际资金与盈亏结论：${summarizePnl(scenario, actual)}`,
  ].join("\n");
}

function algorithmAndExpectedData(scenario, expected, expectedOptions, selectedExpectedIndex) {
  const traces = asArray(expected.calculationTrace);
  const options = asArray(expectedOptions);
  const optionDetails = options.map((option, index) => {
    const checkpoint = asArray(option.checkpoints).at(-1);
    const optionTraces = asArray(option.calculationTrace);
    if (index === selectedExpectedIndex) {
      return [
        `expected 合法分支 ${index + 1}/${options.length}（actual 命中）`,
        "本分支全部 checkpoint 数据与 actual 逐字段相同。为避免把同一份完整数据机械重复两遍：每一步 checkpoint 的完整新增/变化值见“详细操作步骤”，最终累计完整值见“最终 actual 检查点完整数据”，算法轨迹见上方，逐组件相等证据见“实际/预期比对结论”。",
        "因此，本分支完整 checkpoint 数据与“最终 actual 检查点完整数据”逐字段相同；这是等值引用，不是省略校验。",
      ].join("\n");
    }
    return [
      `expected 合法分支 ${index + 1}/${options.length}（本次未命中但属于合法竞态结果）`,
      `分支算法轨迹（${optionTraces.length} 条）：${optionTraces.length > 0 ? optionTraces.map((trace, traceIndex) => `${traceIndex + 1}. ${trace}`).join("；") : "无独立 trace"}`,
      "未命中分支的完整 expected 最终检查点：",
      formatCompleteSnapshot(checkpoint?.snapshot, checkpoint?.failure),
    ].join("\n");
  });
  return [
    `核心算法：${describeAlgorithm(scenario)}`,
    `算法计算轨迹（actual 命中分支，共 ${traces.length} 条）：${traces.length > 0 ? traces.map((trace, index) => `${index + 1}. ${trace}`).join("；") : "无独立 trace"}`,
    `完整 expected 校验数据（共 ${options.length} 个合法分支）：`,
    ...optionDetails,
  ].join("\n");
}

export function buildCaseRecord(scenario, actual, expected, expectedOptions = [expected], selectedExpectedIndex = 0) {
  if (!scenario?.caseId || !actual || actual.caseId !== scenario.caseId) {
    throw new Error(`场景与 actual 证据不匹配：${scenario?.caseId ?? "<missing>"}`);
  }
  if (!expected || expected.caseId !== scenario.caseId) {
    throw new Error(`场景与 expected 证据不匹配：${scenario?.caseId ?? "<missing>"}`);
  }
  const comparison = buildComparison(actual, expected, asArray(expectedOptions).length, selectedExpectedIndex);
  return {
    caseId: scenario.caseId,
    productType: scenario.productType,
    outcome: scenario.expectedError ? "EXPECTED_REJECTION" : "EXPECTED_SUCCESS",
    categoryId: classifyScenario(scenario),
    scenario: scenarioName(scenario),
    conditions: describeConditions(scenario, actual),
    tradeProcess: describeTimeline(scenario, actual),
    pnlSummary: summarizePnl(scenario, actual),
    algorithm: describeAlgorithm(scenario),
    scenarioAndInitialData: scenarioAndInitialData(scenario),
    detailedSteps: detailedSteps(scenario, actual, expected),
    actualFinalData: actualFinalData(scenario, actual),
    algorithmAndExpectedData: algorithmAndExpectedData(scenario, expected, expectedOptions, selectedExpectedIndex),
    comparisonResult: comparison.comparisonResult,
    comparisonPassed: comparison.comparisonPassed,
    result: scenario.expectedError
      ? `按预期拒绝（${scenario.expectedError}），actual 与 expected ${comparison.comparisonPassed ? "完全相等，测试成功" : "不相等"}`
      : `符合预期，actual 与 expected ${comparison.comparisonPassed ? "完全相等，测试成功" : "不相等"}`,
  };
}

function parseTestReport(reportText) {
  const total = Number(reportText.match(/- Total cases:\s*(\d+)/)?.[1]);
  const passed = Number(reportText.match(/- Passed:\s*(\d+)/)?.[1]);
  const failed = Number(reportText.match(/- Failed:\s*(\d+)/)?.[1]);
  const testGeneratedAt = reportText.match(/- Generated \(UTC\):\s*(\S+)/)?.[1];
  if (
    !/Status:\s*\*\*PASSED\*\*/.test(reportText) ||
    total !== 191 ||
    passed !== 191 ||
    failed !== 0 ||
    !testGeneratedAt
  ) {
    throw new Error("spot-perp-test-report.md 未证明 191 个场景全部通过");
  }
  return { totalCases: total, passedCases: passed, failedCases: failed, testGeneratedAt };
}

export function loadScenarioEvidence(platformRoot) {
  const root = resolve(platformRoot);
  if (evidenceCache.has(root)) {
    return evidenceCache.get(root);
  }

  const matrixPath = resolve(root, "docs/testing/spot-perp-scenario-matrix.json");
  const reportPath = resolve(root, "docs/testing/spot-perp-test-report.md");
  const scenarios = parseJson(matrixPath);
  if (!Array.isArray(scenarios)) {
    throw new Error("场景矩阵必须是 JSON 数组");
  }
  const uniqueIds = new Set(scenarios.map((scenario) => scenario.caseId));
  if (scenarios.length !== 191 || uniqueIds.size !== 191) {
    throw new Error(`场景矩阵覆盖错误：rows=${scenarios.length}, unique=${uniqueIds.size}`);
  }

  const reportText = readFileSync(reportPath, "utf8");
  const reportSummary = parseTestReport(reportText);
  const actualByCaseId = new Map();
  const expectedByCaseId = new Map();
  const expectedOptionsByCaseId = new Map();
  const records = scenarios.map((scenario, index) => {
    const actualPath = resolve(root, "backend/target/scenario-artifacts", scenario.caseId, "actual.json");
    const expectedPath = resolve(root, "backend/target/scenario-artifacts", scenario.caseId, "expected.json");
    const actual = parseJson(actualPath);
    const expectedDocument = parseJson(expectedPath);
    if (!Array.isArray(expectedDocument) || expectedDocument.length < 1) {
      throw new Error(`${scenario.caseId} 的 expected.json 必须是至少包含一个合法分支的数组`);
    }
    if (actual.caseId !== scenario.caseId) {
      throw new Error(`${scenario.caseId} 的 actual.caseId 为 ${actual.caseId}`);
    }
    if (expectedDocument.some((candidate) => candidate.caseId !== scenario.caseId)) {
      throw new Error(`${scenario.caseId} 的 expected.json 包含其他 caseId`);
    }
    const matchingExpectedIndexes = expectedDocument
      .map((candidate, candidateIndex) => isDeepStrictEqual(candidate.checkpoints, actual.checkpoints) ? candidateIndex : -1)
      .filter((candidateIndex) => candidateIndex >= 0);
    if (matchingExpectedIndexes.length !== 1) {
      throw new Error(`${scenario.caseId} 必须且只能命中一个 expected 合法分支，实际命中 ${matchingExpectedIndexes.length} 个`);
    }
    const selectedExpectedIndex = matchingExpectedIndexes[0];
    const expected = expectedDocument[selectedExpectedIndex];
    const failures = asArray(actual.checkpoints).map((checkpoint) => checkpoint.failure).filter(Boolean);
    if (scenario.expectedError) {
      if (!failures.some((failure) => failure.code === scenario.expectedError)) {
        throw new Error(`${scenario.caseId} 预期 ${scenario.expectedError}，但 checkpoint.failure 不匹配`);
      }
    } else if (failures.length > 0) {
      throw new Error(`${scenario.caseId} 是成功路径，却出现 failure=${failures[0].code}`);
    }
    actualByCaseId.set(scenario.caseId, actual);
    expectedByCaseId.set(scenario.caseId, expected);
    expectedOptionsByCaseId.set(scenario.caseId, expectedDocument);
    const record = {
      caseNumber: index + 1,
      ...buildCaseRecord(scenario, actual, expected, expectedDocument, selectedExpectedIndex),
    };
    if (!record.comparisonPassed) {
      throw new Error(`${scenario.caseId} 的 actual 与 expected 不相等：${record.comparisonResult}`);
    }
    return record;
  });

  const spotCount = records.filter((record) => record.productType === "CRYPTO_SPOT").length;
  const perpCount = records.filter((record) => record.productType === "LINEAR_PERP").length;
  const successCount = records.filter((record) => record.outcome === "EXPECTED_SUCCESS").length;
  const rejectionCount = records.filter((record) => record.outcome === "EXPECTED_REJECTION").length;
  if (spotCount !== 36 || perpCount !== 155 || successCount !== 163 || rejectionCount !== 28) {
    throw new Error(`矩阵计数不符：Spot=${spotCount}, Perp=${perpCount}, success=${successCount}, rejection=${rejectionCount}`);
  }

  const categories = CATEGORY_DEFINITIONS.map((category) => ({
    ...category,
    records: records.filter((record) => record.categoryId === category.id),
  }));
  if (categories.some((category) => category.records.length === 0)) {
    throw new Error("九个业务分类必须全部非空");
  }
  if (categories.reduce((sum, category) => sum + category.records.length, 0) !== 191) {
    throw new Error("分类后的记录总数不是 191");
  }

  const evidence = {
    platformRoot: root,
    scenarios,
    actualByCaseId,
    expectedByCaseId,
    expectedOptionsByCaseId,
    records,
    categories,
    reportText,
    summary: {
      ...reportSummary,
      spotCases: spotCount,
      perpetualCases: perpCount,
      successfulPaths: successCount,
      expectedRejections: rejectionCount,
    },
  };
  evidenceCache.set(root, evidence);
  return evidence;
}

function representativeCasesMarkdown(evidence) {
  const spotScenario = evidence.scenarios.find((scenario) => scenario.caseId === "SPOT_SELL_PROFIT");
  const spotActual = evidence.actualByCaseId.get("SPOT_SELL_PROFIT");
  const spotRecord = evidence.records.find((record) => record.caseId === "SPOT_SELL_PROFIT");
  const spotSnapshot = finalSnapshot(spotActual);
  const spotBuy = asArray(spotSnapshot.trades).find((trade) => trade.side === "BUY");
  const spotSell = asArray(spotSnapshot.trades).find((trade) => trade.side === "SELL");
  const spotRealized = sumScaled8(asArray(spotSnapshot.positions).map((position) => position.realizedPnl));
  const spotBuyBudget = spotScenario.actions.find((action) => action.type === "BUY")?.quantity;

  const perpScenario = evidence.scenarios.find((scenario) => scenario.caseId === "PERP_CLOSE_PROFIT");
  const perpActual = evidence.actualByCaseId.get("PERP_CLOSE_PROFIT");
  const perpRecord = evidence.records.find((record) => record.caseId === "PERP_CLOSE_PROFIT");
  const perpSnapshot = finalSnapshot(perpActual);
  const perpTrades = asArray(perpSnapshot.trades);
  const perpOpen = perpTrades[0];
  const perpClose = perpTrades.at(-1);
  const perpRealized = sumScaled8(perpTrades.map((trade) => trade.realizedPnl));
  const perpNet = toScaled8(perpSnapshot.account?.balance) - toScaled8(perpScenario.initialBalances?.USDT);

  return [
    "## 两个一眼能看懂的真实案例",
    "",
    `**测试用例1：现货** 用 ${format8(spotBuyBudget)} USDT 预算，在 ${format8(spotBuy.price)} USDT 实际买入 ${format8(spotBuy.quantity)} BTC（手续费 ${format8(spotBuy.fee)} ${spotBuy.feeAsset}），随后在 ${format8(spotSell.price)} USDT 卖出 ${format8(spotSell.quantity)} BTC（手续费 ${format8(spotSell.fee)} ${spotSell.feeAsset}），系统实际已实现盈利 ${formatScaled8(spotRealized)} USDT。${spotRecord.algorithm} ${spotRecord.result}。`,
    "",
    `**测试用例2：永续合约** BTCUSDT-PERP 使用 ${perpScenario.leverage}x 全仓，在 ${format8(perpOpen.price)} USDT 做多 ${format8(perpOpen.quantity)} BTC，在 ${format8(perpClose.price)} USDT 以 reduceOnly 全部平仓 ${format8(perpClose.quantity)} BTC；交易毛盈亏 ${formatScaled8(perpRealized)} USDT，扣除开平仓手续费后净资金增加 ${formatScaled8(perpNet)} USDT。${perpRecord.algorithm} ${perpRecord.result}。`,
    "",
    "这里的实际证据只有 BTCUSDT 和 BTCUSDT-PERP；没有 ETH 成交数据，因此报告不会把 BTC 案例改写成 ETH。",
  ].join("\n");
}

function canonicalSources(generatedAt) {
  const manifestSources = [
    {
      id: "scenario_matrix",
      label: "191 个 Spot/Perpetual 场景矩阵",
      path: "docs/testing/spot-perp-scenario-matrix.json",
    },
    {
      id: "scenario_actuals",
      label: "逐用例实际执行快照",
      path: "backend/target/scenario-artifacts",
    },
    {
      id: "scenario_expecteds",
      label: "逐用例算法预期快照与计算轨迹",
      path: "backend/target/scenario-artifacts",
    },
    {
      id: "scenario_test_report",
      label: "Spot + Linear Perpetual DEMO 最终测试报告",
      path: "docs/testing/spot-perp-test-report.md",
    },
    {
      id: "scenario_evidence_join",
      label: "按 caseId 关联的矩阵、actual 与 expected 证据",
      path: "scripts/generate-readable-trading-scenario-report.mjs",
    },
  ];
  const descriptions = {
    scenario_matrix: "复核 191 个场景定义、产品类型、动作、价格路径和预期错误码；报告生成器直接读取同一路径。",
    scenario_actuals: "复核每个 caseId 的 actual.json 检查点快照；报告生成器直接读取同一路径。",
    scenario_expecteds: "复核每个 caseId 的 expected.json 合法分支、检查点快照与 calculationTrace；报告生成器直接读取同一路径。",
    scenario_test_report: "复核最终测试状态、总数、通过数和产品覆盖统计；报告生成器直接读取同一路径。",
    scenario_evidence_join: "按 caseId 复核矩阵、actual 与 expected 原始证据并逐组件深比较；中文叙事由 Node 生成器对同一数据转换。",
  };
  const sqlBySourceId = {
    scenario_matrix: "SELECT * FROM read_json_auto('docs/testing/spot-perp-scenario-matrix.json', format = 'array')",
    scenario_actuals: "SELECT * FROM read_json_auto('backend/target/scenario-artifacts/*/actual.json', union_by_name = true, filename = true)",
    scenario_expecteds: "SELECT * FROM read_json_auto('backend/target/scenario-artifacts/*/expected.json', union_by_name = true, filename = true)",
    scenario_test_report: "SELECT filename, content FROM read_text('docs/testing/spot-perp-test-report.md')",
    scenario_evidence_join: [
      "WITH matrix AS (",
      "  SELECT * FROM read_json_auto('docs/testing/spot-perp-scenario-matrix.json', format = 'array')",
      "), actuals AS (",
      "  SELECT * FROM read_json_auto('backend/target/scenario-artifacts/*/actual.json', union_by_name = true, filename = true)",
      "), expecteds AS (",
      "  SELECT * FROM read_json_auto('backend/target/scenario-artifacts/*/expected.json', union_by_name = true, filename = true)",
      ")",
      "SELECT matrix.*, actuals.checkpoints AS actual_checkpoints, expecteds.checkpoints AS expected_checkpoints",
      "FROM matrix JOIN actuals USING (caseId) JOIN expecteds USING (caseId)",
    ].join("\n"),
  };
  const topLevelSources = manifestSources.map((source) => ({
    ...source,
    query: {
      engine: "duckdb",
      id: `${source.id}-read`,
      description: descriptions[source.id],
      executed_at: generatedAt,
      language: "sql",
      sql: sqlBySourceId[source.id],
      ...(source.id === "scenario_evidence_join"
        ? {
            tables_used: [
              "docs/testing/spot-perp-scenario-matrix.json",
              "backend/target/scenario-artifacts/<caseId>/actual.json",
              "backend/target/scenario-artifacts/<caseId>/expected.json",
            ],
          }
        : {}),
    },
  }));
  return { manifestSources, topLevelSources };
}

function splitTextForArtifact(value, maximumLength = 3500) {
  const chunks = [];
  let remaining = String(value ?? "");
  if (remaining === "") {
    return [""];
  }
  const delimiters = ["\n\n", "\n", "。 ", "。", "；", "，"];
  while (remaining.length > maximumLength) {
    let cutAt = -1;
    for (const delimiter of delimiters) {
      const candidate = remaining.lastIndexOf(delimiter, maximumLength);
      if (candidate >= Math.floor(maximumLength * 0.6)) {
        cutAt = candidate + delimiter.length;
        break;
      }
    }
    if (cutAt < 1) {
      cutAt = maximumLength;
    }
    chunks.push(remaining.slice(0, cutAt));
    remaining = remaining.slice(cutAt);
  }
  chunks.push(remaining);
  return chunks;
}

function expandAuditRecord(record) {
  const displayedFields = [
    ["initial", "scenarioAndInitialData"],
    ["steps", "detailedSteps"],
    ["actual", "actualFinalData"],
    ["expected", "algorithmAndExpectedData"],
    ["check", "comparisonResult"],
  ];
  const chunksByField = Object.fromEntries(
    displayedFields.map(([outputField, recordField]) => [outputField, splitTextForArtifact(record[recordField])]),
  );
  const segmentCount = Math.max(...displayedFields.map(([field]) => chunksByField[field].length));
  return Array.from({ length: segmentCount }, (_, index) => ({
    case: `${String(record.caseNumber).padStart(3, "0")} · ${record.caseId} · ${String(index + 1).padStart(2, "0")}/${String(segmentCount).padStart(2, "0")}`,
    ...Object.fromEntries(
      displayedFields.map(([field]) => [field, chunksByField[field][index] ?? "—"]),
    ),
  }));
}

export function buildReportArtifact(evidence, generatedAt = new Date().toISOString()) {
  const title = "191 个现货与 USDT 线性永续场景逐步验算报告";
  const matchedCases = evidence.records.filter((record) => record.comparisonPassed).length;
  const summaryRow = {
    totalCases: evidence.records.length,
    passedCases: evidence.summary.passedCases,
    failedCases: evidence.summary.failedCases,
    matchedCases,
    spotCases: evidence.summary.spotCases,
    perpetualCases: evidence.summary.perpetualCases,
    successfulPaths: evidence.summary.successfulPaths,
    expectedRejections: evidence.summary.expectedRejections,
  };
  const { manifestSources, topLevelSources } = canonicalSources(generatedAt);
  const cards = [
    ["total_cases", "测试用例总数", "totalCases", "scenario_matrix"],
    ["passed_cases", "实际通过用例", "passedCases", "scenario_test_report"],
    ["failed_cases", "实际失败用例", "failedCases", "scenario_test_report"],
    ["matched_cases", "actual = expected", "matchedCases", "scenario_evidence_join"],
    ["spot_cases", "现货用例", "spotCases", "scenario_matrix"],
    ["perpetual_cases", "永续用例", "perpetualCases", "scenario_matrix"],
    ["expected_rejections", "预期拒绝路径（属于通过）", "expectedRejections", "scenario_matrix"],
  ].map(([id, label, field, sourceId]) => ({
    id,
    dataset: "summary",
    sourceId,
    metrics: [{ label, field, format: "number" }],
  }));
  const categorySummary = evidence.categories.map((category, index) => ({
    categoryOrder: index + 1,
    category: `${index + 1}. ${category.title}`,
    categoryShort: `${index + 1}. ${CATEGORY_SHORT_LABELS[category.id]}`,
    caseCount: category.records.length,
    productScope: index < 3 ? "Spot" : "Linear Perpetual",
  }));
  const largestCategory = evidence.categories.reduce((largest, category) =>
    category.records.length > largest.records.length ? category : largest
  );
  const remainingCategoryCases = evidence.records.length - largestCategory.records.length;
  const charts = [
    {
      id: "category_coverage_chart",
      title: "九类测试用例数量",
      subtitle: "9 个业务分类，共 191 个实际通过用例",
      description: "全部 191 个场景按唯一业务分类汇总；分类使用短标签，纵轴从零开始显示用例数。",
      showDescription: true,
      type: "bar",
      dataset: "category_summary",
      sourceId: "scenario_evidence_join",
      encodings: {
        x: { field: "categoryShort", type: "nominal", label: "业务分类" },
        y: { field: "caseCount", type: "quantitative", label: "用例数" },
      },
    },
  ];

  const columns = [
    { field: "case", label: "编号/分段", sizing: "content" },
    { field: "initial", label: "场景与初始数据", sizing: "content" },
    { field: "steps", label: "详细操作步骤", sizing: "content" },
    { field: "actual", label: "最终实际结果", sizing: "content" },
    { field: "expected", label: "算法与 expected 校验数据", sizing: "content" },
    { field: "check", label: "实际/预期比对结论", sizing: "content" },
  ];
  const tables = evidence.categories.map((category) => ({
    id: `${category.id}_table`,
    title: category.title,
    description: `共 ${category.records.length} 个场景；超长场景拆成连续分段且不截断，编号格式为“矩阵编号 · caseId · 当前段/总段数”。`,
    showDescription: true,
    dataset: category.id,
    sourceId: "scenario_evidence_join",
    columns,
    defaultSort: { field: "case", direction: "asc" },
    density: "spacious",
  }));

  const blocks = [
    { id: "report_title", type: "markdown", body: `# ${title}` },
    {
      id: "technical_summary",
      type: "markdown",
      body: [
        "## 技术摘要",
        "",
        `- **191 个 actual 与 expected 全部完全相等。** 每个 checkpoint 的订单、成交、仓位、钱包、账户、账本、保护单、事件和失败结果都逐项一致。`,
        "- **4 个竞态场景各允许两个合法赢家分支。** 报告保留两套 expected 完整数据，并标明本次 actual 命中了哪一套；其余 187 个场景只有一个 expected 分支。",
        `- **覆盖现货与 USDT 线性永续。** 其中 Spot 36 个、Linear Perpetual 155 个；163 个正常成功路径、28 个按预期拒绝路径。`,
        `- **证据时间可追溯。** 底层测试报告生成于 ${evidence.summary.testGeneratedAt}，本 HTML 的生成时间记录在报告 manifest 中。`,
        "- **每个数字都能追溯。** 下文初始数据与请求来自 matrix，实际结果来自 actual.json，算法结果与计算轨迹来自 expected.json。",
      ].join("\n"),
    },
    { id: "headline_metrics", type: "metric-strip", cardIds: cards.map((card) => card.id) },
    {
      id: "coverage_distribution",
      type: "markdown",
      sourceId: "scenario_evidence_join",
      body: [
        `## ${evidence.records.length} 个用例如何分布`,
        "",
        `**${largestCategory.title}是覆盖重心，共 ${largestCategory.records.length} 个场景。** 其余 ${remainingCategoryCases} 个场景分布在另外八类；柱状图用于快速比较数量，精确交易细节仍以随后九张表为准。`,
      ].join("\n"),
    },
    { id: "category_coverage_chart_block", type: "chart", chartId: "category_coverage_chart" },
    {
      id: "representative_cases",
      type: "markdown",
      sourceId: "scenario_evidence_join",
      body: representativeCasesMarkdown(evidence),
    },
    {
      id: "reading_rules",
      type: "markdown",
      body: [
        "## 统一阅读口径与官方语义",
        "",
        "- 现货买入按“净收币 = 毛买入量 - base 手续费”阅读；卖出收入再扣 quote 手续费。订单语义参考 [Binance Spot REST](https://developers.binance.com/en/docs/products/spot/rest-api)。",
        "- 永续多仓平仓盈亏为“（平仓价 - 开仓均价）× 平仓数量”，空仓方向相反；cash/cross/isolated、posSide、reduceOnly 和 spot market tgtCcy 语义参考 [OKX API v5](https://www.okx.com/docs-v5/en/)。",
        "- 资金费为“结算时仓位价值 × 资金费率”；正费率多头支付空头，参考 [OKX 永续资金费机制](https://www.okx.com/en-us/help/perps-funding-fee-mechanism)。",
        "- 当前矩阵验证每张订单一次全量成交；真实 partial fill 和 DEPTH matching 不在范围内，PARTIALLY_FILLED 只验证被安全拒绝。",
      ].join("\n"),
    },
    {
      id: "comparison_method",
      type: "markdown",
      sourceId: "scenario_evidence_join",
      body: [
        "## 怎样判定“两个结果相等”",
        "",
        "每一步都按 actionIndex/actionId 对齐，再逐项比较 orders、trades、positions、wallets、account、ledger、protections、events、failure。JSON 数字按解析后的数值比较，所以 `0E-8` 与 `0.00000000`、`110.51105000` 与 `110.5110500000` 都属于相等；数组顺序、字符串、布尔值和错误码仍必须完全一致。",
        "",
        "4 个竞态场景在 expected.json 中各有两个合法赢家分支。生成器不会任取第一条，而是要求 actual 必须且只能深度匹配其中一个分支；报告同时展示两套 expected 校验数据并标出实际命中分支。",
      ].join("\n"),
    },
  ];

  for (const [index, category] of evidence.categories.entries()) {
    blocks.push({
      id: `${category.id}_heading`,
      type: "markdown",
      sourceId: "scenario_evidence_join",
      body: `## ${index + 1}. ${category.title}（${category.records.length} 个）\n\n${category.description}`,
    });
    blocks.push({ id: `${category.id}_table_block`, type: "table", tableId: `${category.id}_table` });
  }

  blocks.push(
    {
      id: "next_steps",
      type: "markdown",
      body: [
        "## 如何使用这份报告",
        "",
        "1. 业务验收先看两个代表案例，确认盈亏口径与预期一致。",
        "2. 按九个分类定位规则，再用原始 caseId 回查 actual.json 的订单、成交、仓位、钱包和账本。",
        "3. 后续增加新产品或真实部分成交时，另建矩阵和证据，不能把本报告的一次全量成交结论直接外推。",
      ].join("\n"),
    },
    {
      id: "further_questions",
      type: "markdown",
      body: [
        "## 后续值得继续验证的问题",
        "",
        "- 接入真实撮合深度后，部分成交、撤单剩余量和多笔手续费如何逐笔对账？",
        "- 若未来加入 ETH 或其他品种，应如何按各自 tickSize、stepSize、合约乘数和费率生成独立证据？",
      ].join("\n"),
    },
    {
      id: "scope_and_caveats",
      type: "markdown",
      sourceId: "scenario_test_report",
      body: [
        "## 范围与假设",
        "",
        "本报告只陈述 CRYPTO_SPOT 与 USDT LINEAR_PERP 的 DEMO execution 结果，不代表真实 broker、FIX、LP 或真实资金交易。真实 partial fill、DEPTH matching、保险基金和 ADL 均未纳入本矩阵。",
      ].join("\n"),
    },
  );

  return {
    surface: "report",
    manifest: {
      version: 1,
      surface: "report",
      title,
      generatedAt,
      cards,
      charts,
      tables,
      sources: manifestSources,
      blocks,
    },
    snapshot: {
      version: 1,
      generatedAt,
      status: "ready",
      datasets: {
        summary: [summaryRow],
        category_summary: categorySummary,
        ...Object.fromEntries(
          evidence.categories.map((category) => [category.id, category.records.flatMap(expandAuditRecord)]),
        ),
      },
    },
    sources: topLevelSources,
  };
}

function parseCliArguments(argv) {
  const outputIndex = argv.indexOf("--output");
  if (outputIndex === -1 || !argv[outputIndex + 1]) {
    throw new Error("用法：node scripts/generate-readable-trading-scenario-report.mjs --output <artifact.json>");
  }
  return { output: resolve(argv[outputIndex + 1]) };
}

function runCli() {
  const { output } = parseCliArguments(process.argv.slice(2));
  const platformRoot = dirname(dirname(fileURLToPath(import.meta.url)));
  const evidence = loadScenarioEvidence(platformRoot);
  const artifact = buildReportArtifact(evidence, new Date().toISOString());
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");
  process.stdout.write(`Generated ${evidence.records.length} cases across ${evidence.categories.length} categories: ${output}\n`);
}

const isCli = process.argv[1]
  && pathToFileURL(resolve(process.argv[1])).href === import.meta.url;

if (isCli) {
  try {
    runCli();
  } catch (error) {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  }
}
