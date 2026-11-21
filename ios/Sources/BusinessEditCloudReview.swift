import Foundation

/// Read-only post-edit status of the *cloud snapshot*, not proof that Android
/// has downloaded the change. Source account still controls all cache reads.
struct BusinessEditCloudReview {
    enum Status: Equatable {
        case notVisible
        case waitingForRefresh
        case current
        case newerRevision
        case inconsistent
    }
    let status: Status
    let title: String

    init(job: TestBusinessEditJob, snapshot: ReadOnlySnapshot) {
        guard let row = snapshot.records[job.proposed.logicalKey] else {
            status = .notVisible
            title = "云端缓存未见此记录；刷新后再核对"
            return
        }
        if row.rowVersion < job.proposed.rowVersion {
            status = .waitingForRefresh
            title = "云端缓存版本较旧，需要刷新"
        } else if row.rowVersion > job.proposed.rowVersion {
            status = .newerRevision
            title = "云端已有更新版本，请查看是否来自 Android"
        } else if TestBusinessEditPolicy.confirmed(
            job, events: Array(snapshot.records.values)) {
            status = .current
            title = "云端当前版本与更正一致（不代表 Android 已下载）"
        } else {
            status = .inconsistent
            title = "云端同版本金额或归属不一致，请核对"
        }
    }
}
