# The vendor interface is reached through a Binder stub, so the generated
# PrinterInterface and its Stub/Proxy must survive R8 under their own names.
-keep interface recieptservice.com.recieptservice.PrinterInterface { *; }
-keep class recieptservice.com.recieptservice.PrinterInterface$* { *; }
