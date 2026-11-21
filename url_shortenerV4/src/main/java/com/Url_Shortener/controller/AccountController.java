package com.Url_Shortener.controller;

import com.Url_Shortener.dto.ApiResponse;
import com.Url_Shortener.dto.PlanInfoDto;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.service.UrlMappingService;
import com.Url_Shortener.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final UserService userService;
    private final PlanService planService;
    private final UrlMappingService urlMappingService;
    private final boolean devPlanSwitchEnabled;

    public AccountController(UserService userService, PlanService planService,
                             UrlMappingService urlMappingService,
                             @Value("${app.dev.plan-switch-enabled:false}") boolean devPlanSwitchEnabled) {
        this.userService = userService;
        this.planService = planService;
        this.urlMappingService = urlMappingService;
        this.devPlanSwitchEnabled = devPlanSwitchEnabled;
    }

    @GetMapping("/plan")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<PlanInfoDto>> getPlan(Principal principal) {
        User user = userService.findByUsername(principal.getName());
        return ResponseEntity.ok(ApiResponse.success(planInfo(user)));
    }

    /**
     * Request body for the development plan switch.
     *
     * @param plan      FREE, MONTHLY or YEARLY
     * @param periodEnd end of the paid period; defaults to one month or one
     *                  year from now. Set it in the past to try the grace
     *                  period (less than 7 days ago) or a lapsed plan.
     */
    public record DevPlanRequest(Plan plan, LocalDateTime periodEnd) {
    }

    /**
     * Changes the caller's own plan without payment, for trying out the plan
     * rules until payments exist. Off unless app.dev.plan-switch-enabled is
     * true; never enable it in production.
     */
    @PostMapping("/plan/dev")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<PlanInfoDto>> devChangePlan(@RequestBody DevPlanRequest request,
                                                                  Principal principal) {
        if (!devPlanSwitchEnabled) {
            return ResponseEntity.notFound().build();
        }
        if (request.plan() == null) {
            throw new IllegalArgumentException("plan is required");
        }
        User user = userService.findByUsername(principal.getName());
        LocalDateTime periodEnd = request.periodEnd();
        if (periodEnd == null && request.plan() == Plan.MONTHLY) {
            periodEnd = planService.now().plusMonths(1);
        } else if (periodEnd == null && request.plan() == Plan.YEARLY) {
            periodEnd = planService.now().plusYears(1);
        }
        User updated = planService.changeSubscription(user, request.plan(), periodEnd);
        return ResponseEntity.ok(ApiResponse.success(planInfo(updated), "Plan changed"));
    }

    private PlanInfoDto planInfo(User user) {
        Plan effective = planService.effectivePlan(user);
        return new PlanInfoDto(
                user.getPlan(),
                effective,
                user.getPlanPeriodEnd(),
                planService.inGracePeriod(user),
                planService.graceEndsAt(user),
                effective.maxActiveUrls(),
                urlMappingService.countActiveUrls(user),
                effective.linkLifetime() != null ? effective.linkLifetime().toDays() : null,
                effective.analytics(),
                effective.permanentRedirect() ? 301 : 302,
                devPlanSwitchEnabled);
    }
}
