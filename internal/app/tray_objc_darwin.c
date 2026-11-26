#import <Cocoa/Cocoa.h>
#include <dispatch/dispatch.h>

extern void hiveTrayOpen(void);
extern void hiveTrayQuit(void);
extern void hiveTrayReady(int ok);

@interface HiveTrayTarget : NSObject
- (void)onOpen:(id)sender;
- (void)onQuit:(id)sender;
@end

@implementation HiveTrayTarget
- (void)onOpen:(id)sender {
	(void)sender;
	hiveTrayOpen();
}
- (void)onQuit:(id)sender {
	(void)sender;
	hiveTrayQuit();
}
@end

static HiveTrayTarget *hiveTarget = nil;
static NSStatusItem *hiveItem = nil;

int hive_tray_start(const char *tooltip) {
	NSString *text = tooltip ? [NSString stringWithUTF8String:tooltip] : @"SJTU Hive";
	dispatch_async(dispatch_get_main_queue(), ^{
		int ok = 0;
		if (hiveItem == nil) {
			hiveTarget = [HiveTrayTarget new];
			hiveItem = [[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength];
			if (hiveItem != nil) {
				hiveItem.button.title = @"SJTU";
				NSImage *icon = [[NSApplication sharedApplication] applicationIconImage];
				if (icon != nil) {
					icon.size = NSMakeSize(18, 18);
					hiveItem.button.image = icon;
					hiveItem.button.imagePosition = NSImageLeft;
				}
				hiveItem.button.toolTip = text;

				NSMenu *menu = [[NSMenu alloc] init];
				NSMenuItem *open = [[NSMenuItem alloc] initWithTitle:@"打开 SJTU Hive"
					action:@selector(onOpen:) keyEquivalent:@""];
				open.target = hiveTarget;
				[menu addItem:open];
				[menu addItem:[NSMenuItem separatorItem]];
				NSMenuItem *quit = [[NSMenuItem alloc] initWithTitle:@"退出 SJTU Hive"
					action:@selector(onQuit:) keyEquivalent:@"q"];
				quit.target = hiveTarget;
				[menu addItem:quit];
				hiveItem.menu = menu;
				ok = 1;
			}
		} else {
			ok = 1;
		}
		hiveTrayReady(ok);
	});
	return 1;
}

void hive_tray_stop(void) {
	dispatch_async(dispatch_get_main_queue(), ^{
		if (hiveItem != nil) {
			[[NSStatusBar systemStatusBar] removeStatusItem:hiveItem];
			hiveItem = nil;
		}
		hiveTarget = nil;
	});
}
