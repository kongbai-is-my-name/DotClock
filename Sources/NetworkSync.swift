import Foundation

/// 纯网络时间校准与 IP 自动定位服务（无需定位权限、不读取任何本地文件）。
enum NetworkSync {
    struct LocationResult: Codable, Equatable {
        var name: String
        var tzID: String
        var latitude: Double?
        var longitude: Double?

        var city: City {
            City(name: name, tzID: tzID, latitude: latitude, longitude: longitude)
        }
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 8
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()

    // MARK: - 网络时间同步

    /// 从高可用网络授时源获取标准时间与本机时钟的偏移量（秒）：`网络真实时间 = Date().addingTimeInterval(offset)`。
    /// 自动扣除单程网络往返延迟（RTT / 2），多源顺序容错。
    static func syncTimeOffset(completion: @escaping (TimeInterval?) -> Void) {
        syncFromTaobao { offset in
            if let offset {
                DispatchQueue.main.async { completion(offset) }
                return
            }
            syncFromTencent { offset in
                if let offset {
                    DispatchQueue.main.async { completion(offset) }
                    return
                }
                syncFromHTTPDate(url: "https://www.apple.com") { offset in
                    if let offset {
                        DispatchQueue.main.async { completion(offset) }
                        return
                    }
                    syncFromHTTPDate(url: "https://www.baidu.com") { offset in
                        DispatchQueue.main.async { completion(offset) }
                    }
                }
            }
        }
    }

    /// 源 1：淘宝毫秒级网络授时接口。
    private static func syncFromTaobao(completion: @escaping (TimeInterval?) -> Void) {
        guard let url = URL(string: "https://acs.m.taobao.com/gw/mtop.common.getTimestamp/") else {
            completion(nil)
            return
        }
        let startUptime = ProcessInfo.processInfo.systemUptime
        session.dataTask(with: url) { data, _, _ in
            let rtt = ProcessInfo.processInfo.systemUptime - startUptime
            let localNow = Date()
            guard let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let payload = json["data"] as? [String: Any],
                  let msString = payload["t"] as? String,
                  let ms = Double(msString)
            else {
                completion(nil)
                return
            }
            let networkTime = Date(timeIntervalSince1970: ms / 1000.0 + rtt / 2.0)
            completion(networkTime.timeIntervalSince(localNow))
        }.resume()
    }

    /// 源 2：腾讯秒级网络授时接口。
    private static func syncFromTencent(completion: @escaping (TimeInterval?) -> Void) {
        guard let url = URL(string: "https://vv.video.qq.com/checktime?otype=json") else {
            completion(nil)
            return
        }
        let startUptime = ProcessInfo.processInfo.systemUptime
        session.dataTask(with: url) { data, _, _ in
            let rtt = ProcessInfo.processInfo.systemUptime - startUptime
            let localNow = Date()
            guard let data, var text = String(data: data, encoding: .utf8) else {
                completion(nil)
                return
            }
            if let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}") {
                text = String(text[start...end])
            }
            guard let jsonData = text.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
                  let seconds = (json["t"] as? NSNumber)?.doubleValue
            else {
                completion(nil)
                return
            }
            let networkTime = Date(timeIntervalSince1970: seconds + rtt / 2.0)
            completion(networkTime.timeIntervalSince(localNow))
        }.resume()
    }

    /// 源 3 / 4：通过 HTTPS HEAD 响应头中的 RFC 1123 Date 字段校准时间。
    private static func syncFromHTTPDate(url urlString: String, completion: @escaping (TimeInterval?) -> Void) {
        guard let url = URL(string: urlString) else {
            completion(nil)
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        let startUptime = ProcessInfo.processInfo.systemUptime
        session.dataTask(with: request) { _, response, _ in
            let rtt = ProcessInfo.processInfo.systemUptime - startUptime
            let localNow = Date()
            guard let http = response as? HTTPURLResponse,
                  let dateHeader = http.value(forHTTPHeaderField: "Date"),
                  let serverDate = parseHTTPDate(dateHeader)
            else {
                completion(nil)
                return
            }
            let networkTime = serverDate.addingTimeInterval(rtt / 2.0)
            completion(networkTime.timeIntervalSince(localNow))
        }.resume()
    }

    private static func parseHTTPDate(_ string: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        for format in ["EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy"] {
            formatter.dateFormat = format
            if let date = formatter.date(from: string) { return date }
        }
        return nil
    }

    // MARK: - 网络 IP 自动定位（城市 + 时区 + 经纬度）

    /// 自动通过公网 IP 获取当前所在城市、时区与坐标。
    /// 优先尝试国内直连 IP 接口，避免开启分流代理时被误判到海外代理节点；若不在国内或请求失败，自动回退国际 IP 接口。
    static func fetchLocation(completion: @escaping (LocationResult?) -> Void) {
        locateViaTencent { domestic in
            if let domestic {
                enrichCoordinatesIfNeeded(domestic, completion: completion)
                return
            }
            locateViaIPIP { backupDomestic in
                if let backupDomestic {
                    enrichCoordinatesIfNeeded(backupDomestic, completion: completion)
                    return
                }
                locateViaIPWhois { global in
                    if let global {
                        DispatchQueue.main.async { completion(global) }
                        return
                    }
                    locateViaIPAPI { fallback in
                        DispatchQueue.main.async { completion(fallback) }
                    }
                }
            }
        }
    }

    /// 若定位结果尚未包含经纬度，则通过 Open-Meteo 地理编码补全坐标。
    private static func enrichCoordinatesIfNeeded(_ base: LocationResult, completion: @escaping (LocationResult?) -> Void) {
        if base.latitude != nil && base.longitude != nil {
            DispatchQueue.main.async { completion(base) }
            return
        }
        WeatherService.coordinate(name: base.name, tzID: base.tzID) { coord in
            var enriched = base
            if let coord {
                enriched.latitude = coord.0
                enriched.longitude = coord.1
            }
            DispatchQueue.main.async { completion(enriched) }
        }
    }

    /// 国内直连源 1：腾讯 IP 定位接口。
    private static func locateViaTencent(completion: @escaping (LocationResult?) -> Void) {
        guard let url = URL(string: "https://r.inews.qq.com/api/ip2city") else {
            completion(nil)
            return
        }
        session.dataTask(with: url) { data, _, _ in
            guard let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (json["ret"] as? Int) == 0
            else {
                completion(nil)
                return
            }
            let country = (json["country"] as? String) ?? ""
            let province = (json["province"] as? String) ?? ""
            let rawCity = (json["city"] as? String) ?? ""
            guard country == "中国", !province.isEmpty || !rawCity.isEmpty else {
                completion(nil)
                return
            }
            let cityName = cleanChineseCityName(rawCity.isEmpty ? province : rawCity)
            let tzID = chineseTimeZone(province: province, city: cityName)
            completion(LocationResult(name: cityName, tzID: tzID))
        }.resume()
    }

    /// 国内直连源 2：IPIP 公共 JSON 接口。
    private static func locateViaIPIP(completion: @escaping (LocationResult?) -> Void) {
        guard let url = URL(string: "https://myip.ipip.net/json") else {
            completion(nil)
            return
        }
        session.dataTask(with: url) { data, _, _ in
            guard let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let payload = json["data"] as? [String: Any],
                  let loc = payload["location"] as? [String],
                  loc.first == "中国"
            else {
                completion(nil)
                return
            }
            let province = loc.count > 1 ? loc[1] : ""
            let rawCity = loc.count > 2 ? loc[2] : ""
            let chosen = !rawCity.isEmpty ? rawCity : province
            guard !chosen.isEmpty else {
                completion(nil)
                return
            }
            let cityName = cleanChineseCityName(chosen)
            let tzID = chineseTimeZone(province: province, city: cityName)
            completion(LocationResult(name: cityName, tzID: tzID))
        }.resume()
    }

    /// 国际源 1：ipwho.is（支持中文城市名，直接返回时区与经纬度）。
    private static func locateViaIPWhois(completion: @escaping (LocationResult?) -> Void) {
        guard let url = URL(string: "https://ipwho.is/?lang=zh-CN") else {
            completion(nil)
            return
        }
        session.dataTask(with: url) { data, _, _ in
            guard let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (json["success"] as? Bool) != false,
                  let rawCity = json["city"] as? String, !rawCity.isEmpty,
                  let tzDict = json["timezone"] as? [String: Any],
                  let tzID = tzDict["id"] as? String, TimeZone(identifier: tzID) != nil
            else {
                completion(nil)
                return
            }
            let lat = (json["latitude"] as? NSNumber)?.doubleValue
            let lon = (json["longitude"] as? NSNumber)?.doubleValue
            completion(LocationResult(name: cleanChineseCityName(rawCity), tzID: tzID, latitude: lat, longitude: lon))
        }.resume()
    }

    /// 国际源 2：ipapi.co（HTTPS 备用源）。
    private static func locateViaIPAPI(completion: @escaping (LocationResult?) -> Void) {
        guard let url = URL(string: "https://ipapi.co/json/") else {
            completion(nil)
            return
        }
        session.dataTask(with: url) { data, _, _ in
            guard let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let rawCity = json["city"] as? String, !rawCity.isEmpty,
                  let tzID = json["timezone"] as? String, TimeZone(identifier: tzID) != nil
            else {
                completion(nil)
                return
            }
            let lat = (json["latitude"] as? NSNumber)?.doubleValue
            let lon = (json["longitude"] as? NSNumber)?.doubleValue
            completion(LocationResult(name: rawCity, tzID: tzID, latitude: lat, longitude: lon))
        }.resume()
    }

    private static func cleanChineseCityName(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        for suffix in ["特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区", "自治区"] where s.hasSuffix(suffix) && s.count > suffix.count {
            s.removeLast(suffix.count)
        }
        if s.count > 2, let last = s.last, "市省".contains(last) {
            s.removeLast()
        }
        return s
    }

    private static func chineseTimeZone(province: String, city: String) -> String {
        let combined = province + city
        if combined.contains("香港") { return "Asia/Hong_Kong" }
        if combined.contains("澳门") { return "Asia/Macau" }
        if combined.contains("台湾") || combined.contains("台北") || combined.contains("高雄") { return "Asia/Taipei" }
        return "Asia/Shanghai"
    }
}
