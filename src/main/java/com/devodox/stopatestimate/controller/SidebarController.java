package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.config.AddonProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SidebarController {

    private final AddonProperties addonProperties;

    public SidebarController(AddonProperties addonProperties) {
        this.addonProperties = addonProperties;
    }

    @GetMapping("/sidebar")
    public String sidebar(Model model) {
        model.addAttribute("addonName", addonProperties.getName());
        model.addAttribute("addonKey", addonProperties.getKey());
        return "sidebar";
    }
}
