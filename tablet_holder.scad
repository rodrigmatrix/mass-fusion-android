// ============================================================
// Joy-Con 2 Magnetic Tablet Holder - Huawei MatePad Mini
// ============================================================
// Joy-Con 2 attaches via MAGNETS (no rail). Switch 2 design.
// 3 printable parts. All dimensions parametric.
// ============================================================

/* [Tablet: Huawei MatePad Mini] */
TABLET_W = 198.59;
TABLET_H = 127.27;
TABLET_T = 5.1;

/* [Joy-Con 2 Dimensions] */
JC2_HEIGHT = 116.0;    // attachment edge length
JC2_WIDTH  = 14.4;     // body width/thickness

/* [Attachment Magnets - Match Switch 2 Console Positions] */
MAG_DIA      = 8.0;    // magnet diameter (match Joy-Con 2)
MAG_DEPTH    = 3.3;    // pocket depth for 3mm magnets
MAG_TOP_Y    = 43.0;   // top magnet center Y (from wing center, 0 = middle)
MAG_BOT_Y    = -43.0;  // bottom magnet center Y
// Adjust MAG_TOP_Y and MAG_BOT_Y to match your Joy-Con 2 magnet positions

/* [Tablet Magnets - 10mm x 3mm] */
MAG_TAB_DIA   = 10.0;
MAG_TAB_DEPTH = 3.3;

/* [Flex Cable Channel - Right Wing] */
FC_W      = 10.0;
FC_D      = 3.0;
FC_CAV_W  = 18.0;
FC_CAV_H  = 14.0;
FC_CAV_D  = 6.0;
FC_EXIT_D = 8.0;

/* [General] */
WALL     = 3.0;
BACK_THK = 3.0;
CLIP_D   = 14.0;
TOL      = 0.25;
CORNER_R = 5.0;

/* [Wing Dimensions] */
WING_THK = 10.0;
WING_D   = 14.0;
WING_L   = JC2_HEIGHT + 20;  // slightly taller than Joy-Con edge

/* [M3 Self-Tapping Screws] */
SCREW_CLEAR  = 3.3;
SCREW_PILOT  = 2.8;
SCREW_HEAD   = 6.0;
SCREW_HDEP   = 4.0;

/* [Mount Block] */
MOUNT_W = 10.0;
MOUNT_D = BACK_THK + TABLET_T + 4;

/* [View] */
SHOW_CENTER  = true;
SHOW_LEFT    = true;
SHOW_RIGHT   = true;
SHOW_PHANTOM = false;
ASSEMBLE     = false;

$fn = 72;

// ============================================================
// HELPERS
// ============================================================

module rrect(x, y, z, r) {
    linear_extrude(z) hull()
        for(sx=[-1,1], sy=[-1,1])
            translate([sx*(x/2-r), sy*(y/2-r)]) circle(r=r);
}

module rrect_ctr(x, y, z, r) {
    translate([0,0,-z/2]) rrect(x, y, z, r);
}

module cyl_hole(d, h) {
    cylinder(d=d, h=h);
}

// ============================================================
// CENTER SECTION (same as before, tablet + clips + magnets)
// ============================================================

module mount_blocks(sx) {
    mw = MOUNT_W; md = MOUNT_D; mh = WING_L;
    pw = TABLET_W + TOL*2 + WALL*2;
    translate([sx * (pw/2 + mw/2), 0, 0])
    rrect_ctr(mw, mh, md, 3);
}

module mag_pockets_tablet() {
    pw = TABLET_W + TOL*2 + WALL*2;
    ph = TABLET_H + TOL*2 + WALL*2;
    margin = 14;
    cols = 3; rows = 2;
    sx = (pw - margin*2) / (cols + 1);
    sy = (ph - margin*2) / (rows + 1);
    for(cx=[1:cols], ry=[1:rows])
        translate([-pw/2 + margin + cx*sx, -ph/2 + margin + ry*sy, BACK_THK - MAG_TAB_DEPTH + 0.01]) {
            cylinder(d=MAG_TAB_DIA+TOL, h=MAG_TAB_DEPTH+0.5);
            translate([0,0,MAG_TAB_DEPTH-0.5])
            cylinder(d1=MAG_TAB_DIA+TOL, d2=MAG_TAB_DIA+3, h=1.2);
        }
}

module screw_pilots(sx) {
    pw = TABLET_W + TOL*2 + WALL*2;
    mh = WING_L;
    for(ys=[-mh*0.3, mh*0.3])
        translate([sx*(pw/2+MOUNT_W/2), ys, BACK_THK+TABLET_T/2])
        rotate([0, 90*sx, 0])
        cylinder(d=SCREW_PILOT, h=MOUNT_W+2, center=true);
}

module back_plate() {
    pw = TABLET_W + TOL*2 + WALL*2;
    ph = TABLET_H + TOL*2 + WALL*2;
    difference() {
        union() {
            rrect(pw, ph, BACK_THK, CORNER_R);
            mount_blocks(-1); mount_blocks(1);
        }
        mag_pockets_tablet();
        screw_pilots(-1); screw_pilots(1);
    }
}

module tablet_clip(pos="top") {
    pw = TABLET_W + TOL*2 + WALL*2;
    ys = (pos=="top") ? 1 : -1;
    gap = TABLET_T + TOL;
    y0 = ys * (TABLET_H/2 + TOL + WALL);
    
    difference() {
        union() {
            translate([-pw/2, y0 - ys*WALL/2, 0])
            cube([pw, WALL, BACK_THK+gap+WALL]);
            translate([-pw/2, y0 + ys*(CLIP_D-1), 0])
            cube([pw, WALL+0.5, BACK_THK+gap+WALL*0.6]);
            translate([-pw/2, y0 - ys*WALL/2, BACK_THK+gap+WALL])
            cube([pw, CLIP_D+WALL, WALL]);
        }
        translate([-pw/2+WALL, y0+ys*0.5, BACK_THK-0.01])
        cube([pw-WALL*2, CLIP_D+2, gap+0.02]);
        translate([-pw/2+WALL, y0-ys*3, BACK_THK+gap])
        rotate([-ys*25, 0, 0]) cube([pw-WALL*2, 12, 8]);
    }
}

module center_section() {
    union() {
        back_plate();
        tablet_clip("top");
        tablet_clip("bottom");
    }
}

// ============================================================
// JOY-CON 2 MAGNETIC ATTACHMENT WING
// ============================================================

module magnetic_wing(side="left") {
    // Wing with FLAT magnetic attachment face for Joy-Con 2
    // No rail - Joy-Con 2 snaps on magnetically
    
    sx_sign = (side == "left") ? -1 : 1;
    sy = [-WING_L*0.3, WING_L*0.3];  // screw hole Y positions
    
    module body() {
        // Wing block
        translate([0, 0, WING_D/2])
        cube([WING_THK, WING_L, WING_D], center=true);
        
        // Alignment ridges (guide rails along top/bottom edges)
        // These help position the Joy-Con 2 correctly
        ridge_h = 2.0;
        ridge_w = WING_THK;
        ridge_d = 4.0;
        
        for(ys=[-1, 1]) {
            // Outer face ridge
            translate([
                sx_sign * WING_THK/2,
                ys * (JC2_HEIGHT/2 + ridge_d/4 - 2),
                BACK_THK + TABLET_T/2
            ])
            cube([
                WING_THK + 2,
                ridge_d,
                JC2_WIDTH + ridge_h*2
            ], center=true);
        }
        
        // Side edge guide (vertical ridge on one side)
        translate([
            sx_sign * (WING_THK/2 + 1),
            0,
            BACK_THK + TABLET_T/2
        ])
        cube([2, JC2_HEIGHT, JC2_WIDTH + ridge_h*2], center=true);
    }
    
    module mag_pockets() {
        // Magnet pockets for Joy-Con 2 attachment
        // Positioned at MAG_TOP_Y and MAG_BOT_Y (from wing center)
        for(my = [MAG_TOP_Y, MAG_BOT_Y]) {
            translate([
                sx_sign * (WING_THK/2 + 0.01),
                my,
                BACK_THK + TABLET_T/2
            ])
            rotate([0, 90*sx_sign, 0])
            cylinder(d=MAG_DIA + TOL, h=MAG_DEPTH + 1);
        }
    }
    
    module connector_cutout() {
        // Central cutout for Joy-Con 2 electrical connector
        // The connector pins are in the middle of the attachment edge
        conn_w = 8;
        conn_h = 15;
        conn_d = 3;
        
        translate([
            sx_sign * (WING_THK/2 - conn_d/2 + 0.5),
            0,
            BACK_THK + TABLET_T/2
        ])
        cube([conn_d + 1, conn_h, conn_w], center=true);
    }
    
    module screw_holes() {
        for(yp = sy) {
            // Clearance hole
            translate([0, yp, BACK_THK + TABLET_T/2])
            rotate([0, (side=="left"?90:-90), 0])
            cylinder(d=SCREW_CLEAR, h=WING_THK+2, center=true);
            
            // Counterbore
            translate([
                (side=="left"?-WING_THK/2:WING_THK/2),
                yp,
                BACK_THK + TABLET_T/2
            ])
            rotate([0, (side=="left"?-90:90), 0])
            cylinder(d=SCREW_HEAD, h=SCREW_HDEP+1, center=true);
        }
    }
    
    module flex_channel() {
        if (side == "right") {
            ch_x = WING_THK/2 - FC_D - 2;
            ch_y0 = -WING_L/2 + 25;
            ch_y1 =  WING_L/2 - 12;
            ch_z  = BACK_THK + TABLET_T/2 - FC_CAV_H/2;
            
            translate([ch_x - FC_W/2, ch_y0, ch_z - FC_D/2])
            cube([FC_W, ch_y1 - ch_y0 + 5, FC_D+1]);
            translate([ch_x - FC_CAV_W/2, ch_y0 + 4, ch_z - FC_CAV_D/4])
            cube([FC_CAV_W, FC_CAV_H, FC_CAV_D+1]);
            translate([ch_x - FC_EXIT_D/2, WING_L/2 - 14, ch_z - FC_EXIT_D/2])
            cube([FC_EXIT_D, 12, FC_EXIT_D+2]);
        }
    }
    
    difference() {
        union() {
            body();
        }
        mag_pockets();
        connector_cutout();
        screw_holes();
        flex_channel();
    }
}

// ============================================================
// LAYOUT
// ============================================================

module assembled_view() {
    pw = TABLET_W + TOL*2 + WALL*2;
    
    center_section();
    
    // Wings positioned flush against mount blocks
    lwx = -pw/2 - MOUNT_W/2 - WING_THK/2;
    rwx =  pw/2 + MOUNT_W/2 + WING_THK/2;
    
    translate([lwx, 0, 0]) magnetic_wing("left");
    translate([rwx, 0, 0]) magnetic_wing("right");
    
    if (SHOW_PHANTOM)
        % translate([0, 0, BACK_THK])
        cube([TABLET_W, TABLET_H, TABLET_T], center=true);
}

module print_layout() {
    pw = TABLET_W + TOL*2 + WALL*2;
    gap = 20;
    
    if (SHOW_CENTER)
        color("SteelBlue", 0.85) center_section();
    
    if (SHOW_LEFT)
        color("SteelBlue", 0.85)
        translate([-pw/2-gap, 0, 0])
        rotate([90, 0, 0])
        magnetic_wing("left");
    
    if (SHOW_RIGHT)
        color("IndianRed", 0.85)
        translate([pw/2+gap, 0, 0])
        rotate([90, 0, 0])
        magnetic_wing("right");
}

// ============================================================
// RENDER
// ============================================================

if (ASSEMBLE) {
    assembled_view();
} else {
    print_layout();
}

// ============================================================
// NOTES
// ============================================================
// 
// The magnet positions (MAG_TOP_Y, MAG_BOT_Y) default to ±43mm
// from wing center. You MUST adjust these to match YOUR Joy-Con 2
// magnet positions. Measure the distance from the center of your
// Joy-Con 2 attachment edge to each magnet center.
//
// Open the USDZ grip model to check reference geometry:
//   /Users/rodrigo/Downloads/joycons2 grip.usdz
//
// The alignment ridges on the wings help position the Joy-Con 2.
// Test fit before final assembly and adjust TOL if needed.
//
// ============================================================
