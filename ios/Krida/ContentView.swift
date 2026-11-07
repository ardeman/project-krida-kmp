import SwiftUI
import Shared

struct ContentView: View {
    @ObservedObject var model: RoomModel
    @State private var playingCongklak = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Krida").font(.largeTitle.bold())
            Text(model.status)
            if !model.players.isEmpty {
                Text("Pemain: \(model.players)")
            }

            if !model.inRoom {
                Button("Main congklak (satu perangkat)") { playingCongklak = true }
                    .buttonStyle(.borderedProminent)
                Button("Buat room (BLE)") { model.startHost() }
                    .buttonStyle(.borderedProminent)
                HStack {
                    TextField("Kode room", text: $model.roomCode)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .textFieldStyle(.roundedBorder)
                        .frame(maxWidth: 160)
                    Button("Gabung") { model.join() }
                        .buttonStyle(.bordered)
                        .disabled(!RoomCode.shared.isValid(code: model.roomCode.uppercased()))
                }
            }

            if model.canStart {
                Button("Mulai") { model.startGame() }
                    .buttonStyle(.borderedProminent)
            }

            if let total = model.total {
                Text("Total: \(total) / 10").font(.title)
                HStack {
                    ForEach(1...3, id: \.self) { n in
                        Button("+\(n)") { model.play(Int32(n)) }
                            .buttonStyle(.borderedProminent)
                            .disabled(!model.myTurn)
                    }
                }
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 1.0, green: 0.97, blue: 0.93))
        // `-openCongklak` launch argument: straight to the board (simulator screenshots, UI tests).
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-openCongklak") { playingCongklak = true } }
        .fullScreenCover(isPresented: $playingCongklak) {
            CongklakView { playingCongklak = false }
        }
    }
}
