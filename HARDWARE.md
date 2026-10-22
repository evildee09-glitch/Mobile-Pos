# Hardware checklist (nothing here has been verified on real devices)
Before buying terminals/printers, test with the app's **Settings → Printer & cash drawer → Test print / Open drawer**.

| Check | Why |
|---|---|
| Android 10+ and Google Play services (ML Kit scanning is bundled, works offline) | camera barcode scanning |
| Printer speaks **ESC/POS** (most 58/80 mm thermal printers do) | the only command set implemented |
| Bluetooth *classic* (SPP) printer, paired in Android settings first | BluetoothPrinter uses RFCOMM; BLE-only printers won't work |
| LAN printer on port 9100 with a fixed IP | NetworkEscPosPrinter |
| Cash drawer plugged into the **printer's RJ11/RJ12** port | drawer opens via the printer's kick command |
| Terminals with a *built-in* printer (SRK T606B, Sunmi, iMin, …) | usually need the vendor SDK/AIDL service; not implemented. Ask the seller if the printer is exposed as a standard Bluetooth/serial ESC/POS device |
| USB/Bluetooth barcode scanner in "keyboard" (HID) mode | types into the search box; Enter submits |
| Peso sign | printed as "P" (default printer code page has no ₱) |
