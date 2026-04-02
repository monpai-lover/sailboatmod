package com.monpai.sailboatmod.market.commodity;

import com.monpai.sailboatmod.market.db.MarketDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class CommodityMarketRepository {
    public CommodityDefinition getDefinition(String commodityKey) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT commodity_key, item_id, variant_key, display_name, unit_size, category, trade_enabled FROM commodity_definition WHERE commodity_key = ?")) {
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
                        resultSet.getInt("trade_enabled") == 1
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
                INSERT INTO commodity_definition (commodity_key, item_id, variant_key, display_name, unit_size, category, trade_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(commodity_key) DO UPDATE SET
                    item_id = excluded.item_id,
                    variant_key = excluded.variant_key,
                    display_name = excluded.display_name,
                    unit_size = excluded.unit_size,
                    category = excluded.category,
                    trade_enabled = excluded.trade_enabled
                """)) {
            statement.setString(1, definition.commodityKey());
            statement.setString(2, definition.itemId());
            statement.setString(3, definition.variantKey());
            statement.setString(4, definition.displayName());
            statement.setInt(5, definition.unitSize());
            statement.setString(6, definition.category());
            statement.setInt(7, definition.tradeEnabled() ? 1 : 0);
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

    private Connection connection() throws SQLException {
        return MarketDatabase.getConnection();
    }
}