package com.mes.finance;

import com.mes.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 总账：科目、凭证、试算平衡、期间关账、汇率、应付凭证同步。
 */
@RestController
@RequestMapping("/api/finance")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public class GeneralLedgerController {

    private final FinanceLedgerService ledgerService;
    private final ApVoucherSyncService apVoucherSyncService;

    public GeneralLedgerController(FinanceLedgerService ledgerService,
                                   ApVoucherSyncService apVoucherSyncService) {
        this.ledgerService = ledgerService;
        this.apVoucherSyncService = apVoucherSyncService;
    }

    public record ManualVoucherLine(String accountCode, String summary,
                                    BigDecimal debit, BigDecimal credit) {
    }

    public record ManualVoucherRequest(LocalDate voucherDate, String summary, String currency,
                                       BigDecimal exchangeRate, List<ManualVoucherLine> lines) {
    }

    public record ExchangeRateRequest(String currency, LocalDate rateDate, BigDecimal rate) {
    }

    @GetMapping("/accounts")
    public ApiResponse accounts() {
        return ApiResponse.ok(ledgerService.accounts());
    }

    @GetMapping("/periods")
    public ApiResponse periods() {
        return ApiResponse.ok(ledgerService.periods());
    }

    @PostMapping("/periods/{period}/close")
    @PreAuthorize("hasAuthority('finance:period')")
    public ApiResponse closePeriod(@PathVariable String period) {
        return ApiResponse.ok(ledgerService.closePeriod(period));
    }

    @GetMapping("/vouchers")
    public ApiResponse vouchers(@RequestParam(required = false) String period,
                                @RequestParam(required = false) String bizType,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(ledgerService.vouchers(period, bizType, keyword, page, size));
    }

    @GetMapping("/vouchers/{id}")
    public ApiResponse voucherDetail(@PathVariable long id) {
        return ApiResponse.ok(ledgerService.voucherDetail(id));
    }

    @PostMapping("/vouchers")
    @PreAuthorize("hasAuthority('finance:gl')")
    public ApiResponse createVoucher(@RequestBody ManualVoucherRequest request) {
        List<PostingService.ManualLine> lines = request.lines() == null ? List.of()
                : request.lines().stream()
                .map(line -> new PostingService.ManualLine(
                        line.accountCode(), line.summary(), line.debit(), line.credit()))
                .toList();
        return ApiResponse.ok(ledgerService.createManualVoucher(
                request.voucherDate(), request.summary(), request.currency(),
                request.exchangeRate(), lines));
    }

    @GetMapping("/trial-balance")
    public ApiResponse trialBalance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(ledgerService.trialBalance(from, to));
    }

    @GetMapping("/exchange-rates")
    public ApiResponse exchangeRates(@RequestParam(required = false) String currency) {
        return ApiResponse.ok(ledgerService.exchangeRates(currency));
    }

    @PostMapping("/exchange-rates")
    @PreAuthorize("hasAuthority('finance:rate')")
    public ApiResponse saveRate(@RequestBody ExchangeRateRequest request) {
        ledgerService.saveRate(request.currency(), request.rateDate(), request.rate());
        return ApiResponse.ok();
    }

    @PostMapping("/ap/sync")
    @PreAuthorize("hasAuthority('finance:sync')")
    public ApiResponse syncAp() {
        return ApiResponse.ok(apVoucherSyncService.sync());
    }
}
