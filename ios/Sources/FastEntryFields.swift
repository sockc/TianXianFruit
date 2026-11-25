import SwiftUI
import UIKit

/// A focused number replaces the previous value, including Android's 0/1
/// defaults. The raw string is not silently reformatted or saved on blur:
/// existing purchase/business validation still owns all rounding and totals.
enum TXFastEntryPolicy {
    static func shouldSelectOnFocus(_ text: String) -> Bool {
        !text.isEmpty
    }
}

/// UITextField is used solely for Android-like select-all-on-focus and a
/// keyboard Done button. All mutation stays in the SwiftUI Binding; this
/// component never makes network requests or writes a ledger record.
struct TXFastNumericField: UIViewRepresentable {
    @Binding var text: String
    let label: String
    var enabled = true

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextField {
        let field = UITextField(frame: .zero)
        field.delegate = context.coordinator
        field.borderStyle = .roundedRect
        field.font = UIFont.preferredFont(forTextStyle: .subheadline)
        field.adjustsFontSizeToFitWidth = true
        field.minimumFontSize = 11
        field.textAlignment = .right
        field.keyboardType = .decimalPad
        field.clearButtonMode = .never
        field.autocorrectionType = .no
        field.spellCheckingType = .no
        field.accessibilityLabel = label
        field.placeholder = "0"
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let toolbar = UIToolbar(frame: CGRect(x: 0, y: 0, width: 0, height: 44))
        toolbar.items = [
            UIBarButtonItem(barButtonSystemItem: .flexibleSpace,
                            target: nil, action: nil),
            UIBarButtonItem(title: "完成", style: .done,
                            target: context.coordinator,
                            action: #selector(Coordinator.finishEditing))
        ]
        toolbar.sizeToFit()
        field.inputAccessoryView = toolbar
        return field
    }

    func updateUIView(_ field: UITextField, context: Context) {
        context.coordinator.parent = self
        field.isEnabled = enabled
        field.accessibilityLabel = label
        // A view refresh must not reset the cursor while a person is typing.
        // A programmatic fill (e.g. the latest valid purchase price) must,
        // however, still be visible immediately.
        if field.text != text {
            field.text = text
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: TXFastNumericField
        init(_ parent: TXFastNumericField) { self.parent = parent }

        func textFieldDidBeginEditing(_ textField: UITextField) {
            guard TXFastEntryPolicy.shouldSelectOnFocus(textField.text ?? "") else { return }
            DispatchQueue.main.async { [weak textField] in
                guard let textField, textField.isFirstResponder else { return }
                textField.selectAll(nil)
            }
        }

        func textField(_ textField: UITextField,
                       shouldChangeCharactersIn range: NSRange,
                       replacementString string: String) -> Bool {
            // UIKit handles selection and composing; existing ledger policies
            // reject invalid, excess precision, negative and missing values.
            return true
        }

        func textFieldDidChangeSelection(_ textField: UITextField) {
            let current = textField.text ?? ""
            if parent.text != current {
                parent.text = current
            }
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            let current = textField.text ?? ""
            if parent.text != current { parent.text = current }
        }

        @objc func finishEditing() {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                            to: nil, from: nil, for: nil)
        }
    }
}

/// Shared row with an explicit label so one-handed users can distinguish
/// channels and numeric units even on iPhone X's narrow screen.
struct TXFastAmountInput: View {
    let title: String
    @Binding var value: String
    var enabled = true

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption2).foregroundColor(.secondary)
                .lineLimit(1).minimumScaleFactor(0.8)
            TXFastNumericField(text: $value, label: title, enabled: enabled)
                .frame(height: 36)
        }
        .frame(maxWidth: .infinity)
    }
}
