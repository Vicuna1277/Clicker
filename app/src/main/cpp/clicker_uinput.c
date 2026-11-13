#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <unistd.h>

#define DEVICE_NAME "Clicker Root Virtual Touchscreen"
#define DEVICE_VENDOR 0x18d1
#define DEVICE_PRODUCT 0xc1c1
#define MAX_SLOTS 10

static int emit_event(int fd, __u16 type, __u16 code, __s32 value) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    gettimeofday(&event.time, NULL);
    event.type = type;
    event.code = code;
    event.value = value;

    const ssize_t written = write(fd, &event, sizeof(event));
    if (written != (ssize_t) sizeof(event)) {
        return -1;
    }
    return 0;
}

static int sync_event(int fd) {
    return emit_event(fd, EV_SYN, SYN_REPORT, 0);
}

static int set_abs_info(int fd, __u16 code, int minimum, int maximum) {
#if defined(UI_ABS_SETUP)
    struct uinput_abs_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.code = code;
    setup.absinfo.minimum = minimum;
    setup.absinfo.maximum = maximum;
    setup.absinfo.fuzz = 0;
    setup.absinfo.flat = 0;
    setup.absinfo.resolution = 0;
    return ioctl(fd, UI_ABS_SETUP, &setup);
#else
    (void) fd;
    (void) code;
    (void) minimum;
    (void) maximum;
    errno = ENOTSUP;
    return -1;
#endif
}

static int configure_bits(int fd) {
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) return -1;
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) return -1;
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) return -1;

    if (ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0) return -1;

    if (ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT) < 0) return -1;
    if (ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR) < 0) return -1;
    if (ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X) < 0) return -1;
    if (ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y) < 0) return -1;
    if (ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID) < 0) return -1;
    if (ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOOL_TYPE) < 0) return -1;
    if (ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE) < 0) return -1;

    if (ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) return -1;
    return 0;
}

static int create_uinput_device(int width, int height) {
    if (width <= 0 || height <= 0) {
        errno = EINVAL;
        return -1;
    }

    const int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    if (configure_bits(fd) < 0) {
        const int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }

    int version = 0;
    const int has_modern_api = (ioctl(fd, UI_GET_VERSION, &version) == 0 && version >= 5);

    if (has_modern_api) {
        struct uinput_setup setup;
        memset(&setup, 0, sizeof(setup));
        snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "%s", DEVICE_NAME);
        setup.id.bustype = BUS_VIRTUAL;
        setup.id.vendor = DEVICE_VENDOR;
        setup.id.product = DEVICE_PRODUCT;
        setup.id.version = 1;

        if (set_abs_info(fd, ABS_MT_SLOT, 0, MAX_SLOTS - 1) < 0 ||
            set_abs_info(fd, ABS_MT_TOUCH_MAJOR, 0, 255) < 0 ||
            set_abs_info(fd, ABS_MT_POSITION_X, 0, width - 1) < 0 ||
            set_abs_info(fd, ABS_MT_POSITION_Y, 0, height - 1) < 0 ||
            set_abs_info(fd, ABS_MT_TRACKING_ID, 0, 65535) < 0 ||
            set_abs_info(fd, ABS_MT_TOOL_TYPE, 0, 2) < 0 ||
            set_abs_info(fd, ABS_MT_PRESSURE, 0, 255) < 0) {
            const int saved = errno;
            close(fd);
            errno = saved;
            return -1;
        }

        if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) {
            const int saved = errno;
            close(fd);
            errno = saved;
            return -1;
        }
    } else {
        // Android 15 设备通常都会走上面的 uinput v5+。保留 legacy 配置只是为了
        // 某些厂商内核仍返回旧版本时不直接失败。
        struct uinput_user_dev dev;
        memset(&dev, 0, sizeof(dev));
        snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "%s", DEVICE_NAME);
        dev.id.bustype = BUS_VIRTUAL;
        dev.id.vendor = DEVICE_VENDOR;
        dev.id.product = DEVICE_PRODUCT;
        dev.id.version = 1;

        dev.absmin[ABS_MT_SLOT] = 0;
        dev.absmax[ABS_MT_SLOT] = MAX_SLOTS - 1;
        dev.absmin[ABS_MT_TOUCH_MAJOR] = 0;
        dev.absmax[ABS_MT_TOUCH_MAJOR] = 255;
        dev.absmin[ABS_MT_POSITION_X] = 0;
        dev.absmax[ABS_MT_POSITION_X] = width - 1;
        dev.absmin[ABS_MT_POSITION_Y] = 0;
        dev.absmax[ABS_MT_POSITION_Y] = height - 1;
        dev.absmin[ABS_MT_TRACKING_ID] = 0;
        dev.absmax[ABS_MT_TRACKING_ID] = 65535;
        dev.absmin[ABS_MT_TOOL_TYPE] = 0;
        dev.absmax[ABS_MT_TOOL_TYPE] = 2;
        dev.absmin[ABS_MT_PRESSURE] = 0;
        dev.absmax[ABS_MT_PRESSURE] = 255;

        if (write(fd, &dev, sizeof(dev)) != (ssize_t) sizeof(dev)) {
            const int saved = errno;
            close(fd);
            errno = saved;
            return -1;
        }
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        const int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }

    return fd;
}

static int touch_down(int fd, int x, int y, int tracking_id) {
    if (emit_event(fd, EV_KEY, BTN_TOUCH, 1) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_SLOT, 0) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_TRACKING_ID, tracking_id) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_TOOL_TYPE, MT_TOOL_FINGER) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_TOUCH_MAJOR, 8) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_PRESSURE, 64) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_POSITION_X, x) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_POSITION_Y, y) < 0) return -1;
    return sync_event(fd);
}

static int touch_move(int fd, int x, int y) {
    if (emit_event(fd, EV_ABS, ABS_MT_SLOT, 0) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_POSITION_X, x) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_POSITION_Y, y) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_TOUCH_MAJOR, 8) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_PRESSURE, 64) < 0) return -1;
    return sync_event(fd);
}

static int touch_up(int fd) {
    if (emit_event(fd, EV_ABS, ABS_MT_SLOT, 0) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_TRACKING_ID, -1) < 0) return -1;
    if (emit_event(fd, EV_ABS, ABS_MT_PRESSURE, 0) < 0) return -1;
    if (emit_event(fd, EV_KEY, BTN_TOUCH, 0) < 0) return -1;
    return sync_event(fd);
}

static void print_errno_error(const char *stage) {
    const int e = errno;
    printf("ERROR native %s errno=%d %s\n", stage, e, strerror(e));
    fflush(stdout);
}

JNIEXPORT jint JNICALL
Java_com_vicuna_clicker_root_input_RootNativeDaemon_nativeRun(
        JNIEnv *env,
        jclass clazz,
        jint width,
        jint height) {
    (void) env;
    (void) clazz;

    setvbuf(stdin, NULL, _IOLBF, 0);
    setvbuf(stdout, NULL, _IOLBF, 0);

    const int fd = create_uinput_device((int) width, (int) height);
    if (fd < 0) {
        print_errno_error("create_uinput_device");
        return 10;
    }

    printf("READY\n");
    fflush(stdout);

    char line[256];
    int exit_code = 0;
    while (fgets(line, sizeof(line), stdin) != NULL) {
        char op = 0;
        int x = 0;
        int y = 0;
        int id = 0;

        if (sscanf(line, " %c", &op) != 1) {
            continue;
        }

        if (op == 'Q') {
            break;
        }

        int rc = 0;
        switch (op) {
            case 'D':
                if (sscanf(line, " %c %d %d %d", &op, &x, &y, &id) != 4) {
                    printf("ERROR native bad DOWN command\n");
                    fflush(stdout);
                    continue;
                }
                rc = touch_down(fd, x, y, id);
                break;

            case 'M':
                if (sscanf(line, " %c %d %d", &op, &x, &y) != 3) {
                    printf("ERROR native bad MOVE command\n");
                    fflush(stdout);
                    continue;
                }
                rc = touch_move(fd, x, y);
                break;

            case 'U':
                // id 目前由单指协议保留给 Kotlin 侧，native 单指 slot=0 不必使用它。
                (void) sscanf(line, " %c %d", &op, &id);
                rc = touch_up(fd);
                break;

            case 'P':
                printf("PONG\n");
                fflush(stdout);
                continue;

            default:
                printf("ERROR native unknown command: %c\n", op);
                fflush(stdout);
                continue;
        }

        if (rc < 0) {
            print_errno_error("write_event");
            exit_code = 11;
            break;
        }
    }

    // 尽量避免调用方中途退出后留下“手指仍按下”的状态。
    (void) touch_up(fd);
    (void) ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    printf("BYE\n");
    fflush(stdout);
    return exit_code;
}
