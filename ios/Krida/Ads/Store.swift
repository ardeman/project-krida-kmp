import StoreKit

/// StoreKit 2 for the one non-consumable product, "Remove ads". Ownership comes from
/// `Transaction.currentEntitlements`, re-read on every [refresh]; "Restore purchases" syncs
/// with the App Store first, as App Review expects a restore button.
@MainActor
final class Store {
    /// Localized price from the App Store, or nil while the product is unknown (not set up, offline).
    private(set) var price: String?
    var onChange: () -> Void = {}

    private let onOwned: (Bool) -> Void
    private var product: Product?
    private var updates: Task<Void, Never>?

    init(onOwned: @escaping (Bool) -> Void) {
        self.onOwned = onOwned
        // Purchases finished elsewhere: Ask to Buy approvals, another device, a refund.
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let t) = result { await t.finish() }
                await self?.refresh()
            }
        }
        Task {
            await refresh()
            await loadProduct()
        }
    }

    /// Re-reads what this Apple ID owns.
    func refresh() async {
        var owned = false
        for await result in Transaction.currentEntitlements {
            if case .verified(let t) = result, t.productID == AdIds.removeAds, t.revocationDate == nil { owned = true }
        }
        onOwned(owned)
    }

    func buy() async {
        if product == nil { await loadProduct() }
        guard let product else { return }
        // Cancelled, pending (Ask to Buy) or failed: nothing changes until `Transaction.updates` says so.
        guard case .success(.verified(let t)) = try? await product.purchase() else { return }
        await t.finish()
        await refresh()
    }

    func restore() async {
        try? await AppStore.sync()
        await refresh()
    }

    private func loadProduct() async {
        guard product == nil, let p = try? await Product.products(for: [AdIds.removeAds]).first else { return }
        product = p
        price = p.displayPrice
        onChange()
    }
}
