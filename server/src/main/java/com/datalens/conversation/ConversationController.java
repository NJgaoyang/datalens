package com.datalens.conversation;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import static com.datalens.conversation.ConversationModels.*;
@RestController @RequestMapping("/api/v1/conversations")
public class ConversationController {
 private final ConversationService service;public ConversationController(ConversationService service){this.service=service;}
 @GetMapping public ApiResponse<?> list(HttpServletRequest r){return ApiResponse.success(service.list(),RequestIdFilter.id(r));}
 @PostMapping public ApiResponse<?> create(@Valid @RequestBody Create input,HttpServletRequest r){return ApiResponse.success(service.create(input,RequestIdFilter.id(r)),RequestIdFilter.id(r));}
 @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable String id,HttpServletRequest r){return ApiResponse.success(service.detail(id),RequestIdFilter.id(r));}
 @PostMapping("/{id}/turns") public ApiResponse<?> send(@PathVariable String id,@Valid @RequestBody Send input,HttpServletRequest r){return ApiResponse.success(service.send(id,input,RequestIdFilter.id(r)),RequestIdFilter.id(r));}
 @GetMapping("/{id}/turns/{turn}") public ApiResponse<?> turn(@PathVariable String id,@PathVariable String turn,HttpServletRequest r){return ApiResponse.success(service.turn(id,turn),RequestIdFilter.id(r));}
 @GetMapping("/{id}/turns/{turn}/result") public ApiResponse<?> result(@PathVariable String id,@PathVariable String turn,HttpServletRequest r){return ApiResponse.success(service.result(id,turn),RequestIdFilter.id(r));}
 @PostMapping("/{id}/turns/{turn}/save") public ApiResponse<?> save(@PathVariable String id,@PathVariable String turn,@Valid @RequestBody Save input,HttpServletRequest r){return ApiResponse.success(service.save(id,turn,input,RequestIdFilter.id(r)),RequestIdFilter.id(r));}
}
