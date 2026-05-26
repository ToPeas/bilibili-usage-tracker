import type AbilityConstant from "@ohos:app.ability.AbilityConstant";
import UIAbility from "@ohos:app.ability.UIAbility";
import type Want from "@ohos:app.ability.Want";
import hilog from "@ohos:hilog";
import type window from "@ohos:window";
import UploadWorkerAbility from "@bundle:com.example.biliusage/entry/ets/workability/UploadWorkerAbility";
const DOMAIN = 0x0000;
export default class EntryAbility extends UIAbility {
    onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
        hilog.info(DOMAIN, 'BiliUsage', '%{public}s', 'Ability onCreate');
        // 注册每日定时后台上传任务（类比安卓 BootReceiver + AlarmManager）
        UploadWorkerAbility.registerDailyWork();
    }
    onDestroy(): void {
        hilog.info(DOMAIN, 'BiliUsage', '%{public}s', 'Ability onDestroy');
    }
    onWindowStageCreate(windowStage: window.WindowStage): void {
        hilog.info(DOMAIN, 'BiliUsage', '%{public}s', 'Ability onWindowStageCreate');
        windowStage.loadContent('pages/Index', (err) => {
            if (err.code) {
                hilog.error(DOMAIN, 'BiliUsage', 'Failed to load content. Cause: %{public}s', JSON.stringify(err) ?? '');
                return;
            }
            hilog.info(DOMAIN, 'BiliUsage', '%{public}s', 'Succeeded in loading content');
        });
    }
    onWindowStageDestroy(): void {
        hilog.info(DOMAIN, 'BiliUsage', '%{public}s', 'Ability onWindowStageDestroy');
    }
    onForeground(): void {
        hilog.info(DOMAIN, 'BiliUsage', '%{public}s', 'Ability onForeground');
    }
    onBackground(): void {
        hilog.info(DOMAIN, 'BiliUsage', '%{public}s', 'Ability onBackground');
    }
}
