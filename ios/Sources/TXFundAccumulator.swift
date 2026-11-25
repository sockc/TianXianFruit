import Foundation

/// Pure port of the per-partner accumulation stage of Android AppDatabase.
/// Inputs must already be resolved by the authoritative source (full dated
/// ledger, stable identities, effective transfers and legacy normalization).
/// Never feed this engine the name-grouped, default-zero presentation models.
enum TXFundAccumulator {
    struct Day: Equatable {
        let date: String
        let rawBalance: Double
        let savedBalance: Double?
        let dailyFullySettled: Bool
        let dailySent: Double
        let dailyReceived: Double
        let hasDailyTransfers: Bool
        let standaloneSent: Double
        let standaloneReceived: Double
        let cutoffCleared: Bool
    }

    struct Result: Equatable {
        let date: String
        let residual: Double
        let standaloneAdjustment: Double
        let checkpoint: Bool
        let endBalance: Double
    }

    enum InvalidInput: Error {
        case duplicateDate, invalidMoney, incompleteSettlement
    }

    // Kotlin round(v * 100) uses ties-to-even, not Swift's default ties-away.
    static func roundMoney(_ value: Double) -> Double {
        (value * 100).rounded(.toNearestOrEven) / 100
    }

    static func ledger(_ days: [Day]) throws -> [Result] {
        guard Set(days.map(\.date)).count == days.count else {
            throw InvalidInput.duplicateDate
        }
        var cumulative = 0.0
        return try days.sorted { $0.date < $1.date }.map { day in
            let amounts = [day.rawBalance, day.savedBalance ?? 0,
                           day.dailySent, day.dailyReceived,
                           day.standaloneSent, day.standaloneReceived]
            guard amounts.allSatisfy({ $0.isFinite && abs($0) < 1e12 }),
                  day.dailySent >= 0, day.dailyReceived >= 0,
                  day.standaloneSent >= 0, day.standaloneReceived >= 0 else {
                throw InvalidInput.invalidMoney
            }
            guard day.savedBalance != nil || !day.dailyFullySettled else {
                throw InvalidInput.incompleteSettlement
            }
            let base = day.savedBalance ?? day.rawBalance
            let residual = day.savedBalance != nil && day.dailyFullySettled
                ? 0 : roundMoney(base + day.dailySent - day.dailyReceived)
            let adjustment = roundMoney(day.standaloneSent - day.standaloneReceived)
            let cleared = day.savedBalance.map {
                abs($0) > 0.005 && (day.dailyFullySettled ||
                    (day.hasDailyTransfers && abs(residual) <= 0.005))
            } ?? false
            let checkpoint = cleared || day.cutoffCleared
            cumulative = checkpoint ? 0 : roundMoney(cumulative + residual + adjustment)
            guard cumulative.isFinite else { throw InvalidInput.invalidMoney }
            return Result(date: day.date, residual: residual,
                          standaloneAdjustment: adjustment, checkpoint: checkpoint,
                          endBalance: cumulative)
        }
    }

    /// Settlement center is the negative of all non-center partner balances.
    /// Callers must supply every counterparty from the same complete cutoff.
    static func centerBalance(_ counterpartBalances: [Double]) throws -> Double {
        guard counterpartBalances.allSatisfy({ $0.isFinite && abs($0) < 1e12 }) else {
            throw InvalidInput.invalidMoney
        }
        let result = roundMoney(-counterpartBalances.reduce(0, +))
        guard result.isFinite else { throw InvalidInput.invalidMoney }
        return result
    }
}
