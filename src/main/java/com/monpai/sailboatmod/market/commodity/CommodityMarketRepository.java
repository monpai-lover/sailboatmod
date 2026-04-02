package com.monpai.sailboatmod.market.commodity;

import com.monpai.sailboatmod.market.db.MarketDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class CommodityMarketRepository {
    public CommodityDefinition getDefinition(String commodityKey) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT commodity_key, item_id, variant_key, display_name, unit_size, category, trade_enabled, rarity, importance, volume, elasticity, base_volatility FROM commodity_definition WHERE commodity_key = ?")) {
            statement.setString(1, commodityKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new CommodityDefinition(
                        resultSet.getString("commodity_key"),
                        resultSet.getString("item_id"),
                        resultSet.getString("variant_key"),
                        resultSet.getString("display_name"),
                        resultSet.getInt("unit_size"),
                        resultSet.getString("category"),
                        resultSet.getInt("trade_enabled") == 1,
                        resultSet.getInt("rarity"),
                        resultSet.getInt("importance"),
                        resultSet.getInt("volume"),
                        resultSet.getInt("elasticity"),
                        resultSet.getInt("base_volatility")
                );
            }
        }
    }

    public CommodityMarketState getState(String commodityKey) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT commodity_key, base_price, current_stock, volatility, spread_bp, stock_floor, stock_ceil, price_floor, price_ceil, last_trade_at, updated_at, version FROM commodity_market_state WHERE commodity_key = ?")) {
            statement.setString(1, commodityKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new CommodityMarketState(
                        resultSet.getString("commodity_key"),
                        resultSet.getInt("base_price"),
                        resultSet.getInt("current_stock"),
                        resultSet.getInt("volatility"),
                        resultSet.getInt("spread_bp"),
                        resultSet.getInt("stock_floor"),
                        resultSet.getInt("stock_ceil"),
                        resultSet.getInt("price_floor"),
                        resultSet.getInt("price_ceil"),
                        resultSet.getLong("last_trade_at"),
                        resultSet.getLong("updated_at"),
                        resultSet.getInt("version")
                );
            }
        }
    }

    public void upsertDefinition(CommodityDefinition definition) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                """
                INSERT INTO commodity_definition (commodity_key, item_id, variant_key, display_name, unit_size, category, trade_enabled, rarity, importance, volume, elasticity, base_volatility)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(commodity_key) DO UPDATE SET
                    item_id = excluded.item_id,
                    variant_key = excluded.variant_key,
                    display_name = excluded.display_name,
                    unit_size = excluded.unit_size,
                    category = excluded.category,
                    trade_enabled = excluded.trade_enabled,
                    rarity = excluded.rarity,
                    importance = excluded.importance,
                    volume = excluded.volume,
                    elasticity = excluded.elasticity,
                    base_volatility = excluded.base_volatility
                """)) {
            statement.setString(1, definition.commodityKey());
            statement.setString(2, definition.itemId());
            statement.setString(3, definition.variantKey());
            statement.setString(4, definition.displayName());
            statement.setInt(5, definition.unitSize());
            statement.setString(6, definition.category());
            statement.setInt(7, definition.tradeEnabled() ? 1 : 0);
            statement.setInt(8, definition.rarity());
            statement.setInt(9, definition.importance());
            statement.setInt(10, definition.volume());
            statement.setInt(11, definition.elasticity());
            statement.setInt(12, definition.baseVolatility());
            statement.executeUpdate();
        }
    }

    public void upsertState(CommodityMarketState state) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                """
                INSERT INTO commodity_market_state (commodity_key, base_price, current_stock, volatility, spread_bp, stock_floor, stock_ceil, price_floor, price_ceil, last_trade_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(commodity_key) DO UPDATE SET
                    base_price = excluded.base_price,
                    current_stock = excluded.current_stock,
                    volatility = excluded.volatility,
                    spread_bp = excluded.spread_bp,
                    stock_floor = excluded.stock_floor,
                    stock_ceil = excluded.stock_ceil,
                    price_floor = excluded.price_floor,
                    price_ceil = excluded.price_ceil,
                    last_trade_at = excluded.last_trade_at,
                    updated_at = excluded.updated_at,
                    version = excluded.version
                """)) {
            statement.setString(1, state.commodityKey());
            statement.setInt(2, state.basePrice());
            statement.setInt(3, state.currentStock());
            statement.setInt(4, state.volatility());
            statement.setInt(5, state.spreadBp());
            statement.setInt(6, state.stockFloor());
            statement.setInt(7, state.stockCeil());
            statement.setInt(8, state.priceFloor());
            statement.setInt(9, state.priceCeil());
            statement.setLong(10, state.lastTradeAt());
            statement.setLong(11, state.updatedAt());
            statement.setInt(12, state.version());
            statement.executeUpdate();
        }
    }

    public void appendTrade(CommodityTradeRecord tradeRecord) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                """
                INSERT INTO commodity_trade_history (commodity_key, trade_side, quantity, unit_price, total_price, source_market_pos, source_nation_id, target_nation_id, actor_uuid, actor_name, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, tradeRecord.commodityKey());
            statement.setString(2, tradeRecord.tradeSide().name());
            statement.setInt(3, tradeRecord.quantity());
            statement.setInt(4, tradeRecord.unitPrice());
            statement.setInt(5, tradeRecord.totalPrice());
            statement.setString(6, tradeRecord.sourceMarketPos());
            statement.setString(7, tradeRecord.sourceNationId());
            statement.setString(8, tradeRecord.targetNationId());
            statement.setString(9, tradeRecord.actorUuid());
            statement.setString(10, tradeRecord.actorName());
            statement.setLong(11, tradeRecord.createdAt());
            statement.executeUpdate();
        }
    }

    public PlayerMarketSettings getPlayerSettings(String playerUuid) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT player_uuid, buy_price_adjustment_bp, sell_price_adjustment_bp FROM player_market_settings WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new PlayerMarketSettings(
                        resultSet.getString("player_uuid"),
                        resultSet.getInt("buy_price_adjustment_bp"),
                        resultSet.getInt("sell_price_adjustment_bp")
                );
            }
        }
    }

    public void upsertPlayerSettings(PlayerMarketSettings settings) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                """
                INSERT INTO player_market_settings (player_uuid, buy_price_adjustment_bp, sell_price_adjustment_bp)
                VALUES (?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    buy_price_adjustment_bp = excluded.buy_price_adjustment_bp,
                    sell_price_adjustment_bp = excluded.sell_price_adjustment_bp
                """)) {
            statement.setString(1, settings.playerUuid());
            statement.setInt(2, settings.buyPriceAdjustmentBp());
            statement.setInt(3, settings.sellPriceAdjustmentBp());
            statement.executeUpdate();
        }
    }

    public void createBuyOrder(BuyOrder order) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                """
                INSERT INTO buy_order (order_id, buyer_uuid, buyer_name, commodity_key, quantity, min_price_bp, max_price_bp, created_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, order.orderId());
            statement.setString(2, order.buyerUuid());
            statement.setString(3, order.buyerName());
            statement.setString(4, order.commodityKey());
            statement.setInt(5, order.quantity());
            statement.setInt(6, order.minPriceBp());
            statement.setInt(7, order.maxPriceBp());
            statement.setLong(8, order.createdAt());
            statement.setString(9, order.status());
            statement.executeUpdate();
        }
    }

    public java.util.List<BuyOrder> listActiveBuyOrders(String commodityKey) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT order_id, buyer_uuid, buyer_name, commodity_key, quantity, min_price_bp, max_price_bp, created_at, status FROM buy_order WHERE commodity_key = ? AND status = 'ACTIVE' ORDER BY created_at DESC")) {
            statement.setString(1, commodityKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<BuyOrder> orders = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    orders.add(new BuyOrder(
                            resultSet.getString("order_id"),
                            resultSet.getString("buyer_uuid"),
                            resultSet.getString("buyer_name"),
                            resultSet.getString("commodity_key"),
                            resultSet.getInt("quantity"),
                            resultSet.getInt("min_price_bp"),
                            resultSet.getInt("max_price_bp"),
                            resultSet.getLong("created_at"),
                            resultSet.getString("status")
                    ));
                }
                return orders;
            }
        }
    }

    public java.util.List<BuyOrder> listActiveBuyOrdersForBuyer(String buyerUuid) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT order_id, buyer_uuid, buyer_name, commodity_key, quantity, min_price_bp, max_price_bp, created_at, status FROM buy_order WHERE buyer_uuid = ? AND status = 'ACTIVE' ORDER BY created_at DESC")) {
            statement.setString(1, buyerUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<BuyOrder> orders = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    orders.add(new BuyOrder(
                            resultSet.getString("order_id"),
                            resultSet.getString("buyer_uuid"),
                            resultSet.getString("buyer_name"),
                            resultSet.getString("commodity_key"),
                            resultSet.getInt("quantity"),
                            resultSet.getInt("min_price_bp"),
                            resultSet.getInt("max_price_bp"),
                            resultSet.getLong("created_at"),
                            resultSet.getString("status")
                    ));
                }
                return orders;
            }
        }
    }

    public void updateBuyOrderStatus(String orderId, String status) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "UPDATE buy_order SET status = ? WHERE order_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, orderId);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return MarketDatabase.getConnection();
    }
}
