import Foundation
import IOKit.ps

struct BatteryStatus {
    let percent: Int
    let charging: Bool?
    let source: String?

    static func current() -> BatteryStatus? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() else { return nil }
        for battery in list as NSArray {
            guard let values = IOPSGetPowerSourceDescription(info, battery as CFTypeRef)?.takeUnretainedValue() as? [String: Any],
                  values[kIOPSTypeKey] as? String == kIOPSInternalBatteryType,
                  (values[kIOPSIsPresentKey] as? Bool) != false,
                  let now = values[kIOPSCurrentCapacityKey] as? Int,
                  let max = values[kIOPSMaxCapacityKey] as? Int,
                  max > 0, now >= 0, now <= max else { continue }
            let power: String?
            switch values[kIOPSPowerSourceStateKey] as? String {
            case kIOPSACPowerValue: power = "ac"
            case kIOPSBatteryPowerValue: power = "battery"
            default: power = nil
            }
            return BatteryStatus(percent: Int((Double(now) / Double(max) * 100).rounded()),
                                 charging: values[kIOPSIsChargingKey] as? Bool,
                                 source: power)
        }
        return nil
    }
}

struct ActivityExtras: Codable {
    let custom_app_name: String
    let custom_description: String?
    let reporter: String
    let battery_percent: Int?
    let battery_charging: Bool?
    let power_source: String?

    init(name: String, description: String? = nil, battery: BatteryStatus?) {
        custom_app_name = name
        custom_description = description
        reporter = "hibi-mac-native"
        battery_percent = battery?.percent
        battery_charging = battery?.charging
        power_source = battery?.source
    }
}
