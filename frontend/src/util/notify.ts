type NotifyFn = (message: string) => void;

let activeNotifier: NotifyFn | null = null;

export function setNotifier(fn: NotifyFn | null): void {
  activeNotifier = fn;
}

export function notify(message: string): void {
  if (activeNotifier) {
    activeNotifier(message);
    return;
  }
  if (typeof window !== "undefined" && typeof window.alert === "function") {
    window.alert(message);
  }
}
