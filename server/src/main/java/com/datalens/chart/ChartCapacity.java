package com.datalens.chart;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Admission only: never removes history or gates revocation/recovery/admin operations. */
@Component
public record ChartCapacity(
        @Value("${datalens.capacity.chart-assets:5000}") int assets,
        @Value("${datalens.capacity.chart-assets-per-owner:500}") int assetsPerOwner,
        @Value("${datalens.capacity.chart-previews:10000}") int previews,
        @Value("${datalens.capacity.chart-previews-per-user:1000}") int previewsPerUser,
        @Value("${datalens.capacity.chart-runs:10000}") int runs,
        @Value("${datalens.capacity.chart-runs-per-user:1000}") int runsPerUser,
        @Value("${datalens.capacity.chart-revisions:20000}") int revisions,
        @Value("${datalens.capacity.chart-revisions-per-chart:1000}") int revisionsPerChart) {
    public ChartCapacity {
        for (int limit : new int[]{assets, assetsPerOwner, previews, previewsPerUser, runs, runsPerUser, revisions, revisionsPerChart}) {
            if (limit < 1 || limit > 1_000_000) throw new IllegalArgumentException("Chart admission limits must be between 1 and 1000000");
        }
        if (assetsPerOwner > assets || previewsPerUser > previews || runsPerUser > runs || revisionsPerChart > revisions)
            throw new IllegalArgumentException("Chart personal limits must not exceed global limits");
    }
    static ChartCapacity defaults() { return new ChartCapacity(5000, 500, 10000, 1000, 10000, 1000, 20000, 1000); }
}
