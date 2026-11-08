import SwiftUI

/// Cream screen with the wordmark and a row of seeds filling one by one, as if sown. Shown at
/// app start until the menu's board snapshot is ready, and before each game until the board's
/// first frames are on screen. It swallows taps so nothing underneath reacts. Same as Android.
struct LoadingScreen: View {
    @Environment(\.uiScale) private var s
    @State private var start = Date()

    /// Holes in the row, like one side of a congklak board.
    private static let holes = 7
    /// One pass: every hole filled, then a short pause with all of them full.
    private static let cycle: TimeInterval = 1.6

    var body: some View {
        VStack(spacing: 22 * s) {
            // Light variant always: the loading screen stays cream even when the system is dark.
            Image("Wordmark").resizable().scaledToFit().frame(height: 48 * s)
                .environment(\.colorScheme, .light)
            TimelineView(.animation) { context in
                let t = context.date.timeIntervalSince(start).truncatingRemainder(dividingBy: Self.cycle)
                let sown = t / Self.cycle * Double(Self.holes + 2)
                HStack(spacing: 10 * s) {
                    ForEach(0..<Self.holes, id: \.self) { i in
                        Circle().fill(Theme.wood).frame(width: 12 * s, height: 12 * s)
                            .opacity(sown > Double(i) ? 1 : 0.22)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.cream)
        .contentShape(Rectangle())
        .onTapGesture {}
        .ignoresSafeArea()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Memuat", bundle: .app))
    }
}
